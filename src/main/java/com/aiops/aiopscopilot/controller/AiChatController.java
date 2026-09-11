package com.aiops.aiopscopilot.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;
import com.aiops.aiopscopilot.common.result.Result;
import com.aiops.aiopscopilot.service.KnowledgeIngester;
import com.aiops.aiopscopilot.service.MetricsService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);

    /** RAG 检索召回条数：先放宽召回数量，再由相似度阈值卡质量 */
    private static final int RAG_TOP_K = 5;
    /** RAG 相似度质量闸门：COSINE 分数低于该值的片段丢弃，保证无关问题正确走"未找到"分支。
     *  0.50 为 bge-m3 中文语料的经验起点，需结合 [RAG] 命中日志中的实际分数断层校准 */
    private static final double RAG_SIMILARITY_THRESHOLD = 0.50;

    private final ChatClient deepseekChatClient;
    private final VectorStore vectorStore;
    private final KnowledgeIngester knowledgeIngester;
    private final MetricsService metricsService;
    private final String reasoningModel;

    public AiChatController(@Qualifier("deepseekChatClient") ChatClient deepseekChatClient,
                           VectorStore vectorStore,
                           KnowledgeIngester knowledgeIngester,
                           MetricsService metricsService,
                           @Value("${spring.ai.ollama.chat.model}") String reasoningModel) {
        this.deepseekChatClient = deepseekChatClient;
        this.vectorStore = vectorStore;
        this.knowledgeIngester = knowledgeIngester;
        this.metricsService = metricsService;
        this.reasoningModel = reasoningModel;
    }

    /**
     * 流式对话接口：以 SSE 逐段推送模型输出，事件按序拼接即为完整回复。
     *
     * 开启 thinking 后 Ollama 会把 deepseek-r1 的思考过程放在单独的 thinking 字段返回，
     * 这里将其重新包装为 <think>...</think> 并与最终回答按序推送，前端可据此区分渲染；
     * 思考内容由模型自行决定，简单问题可能没有思考段。
     *
     * 注意：SSE 响应头在首个事件写入时即提交为 200，此后模型调用出错无法再返回
     * 错误状态码，会改以 [ERROR] 开头的文本事件推送。
     *
     * @param message 用户提问
     * @return SSE 事件流（思考过程 + 最终回答）
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam String message, HttpServletResponse response) {
        // SseEmitter 会把 Content-Type 硬编码为不带 charset 的 text/event-stream，
        // 提前声明响应编码可让 Tomcat 提交响应头时追加 charset=UTF-8，避免浏览器乱码
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        AtomicBoolean thinkingOpened = new AtomicBoolean(false);
        return deepseekChatClient.prompt()
                .options(OllamaChatOptions.builder().enableThinking())
                .user(message)
                .stream()
                .chatResponse()
                .concatMapIterable(chatResponse -> {
                    List<String> chunks = new ArrayList<>();
                    var result = chatResponse.getResult();
                    if (result != null) {
                        String thinking = (String) result.getOutput().getMetadata().get("thinking");
                        if (thinking != null && !thinking.isEmpty()) {
                            if (thinkingOpened.compareAndSet(false, true)) {
                                chunks.add("<think>");
                            }
                            chunks.add(thinking);
                        }
                        String content = result.getOutput().getText();
                        if (content != null && !content.isEmpty()) {
                            if (thinkingOpened.compareAndSet(true, false)) {
                                chunks.add("</think>");
                            }
                            chunks.add(content);
                        }
                    }
                    return chunks;
                })
                .onErrorResume(e -> Flux.just("[ERROR] " + e.getMessage()));
    }

    /**
     * 知识库初始化接口：读取 knowledge.txt → 文本切片 → 调用 Embedding 模型 → 写入 Milvus。
     * 需先在本地启动 Milvus（docker）并拉取 Ollama embedding 模型（ollama pull bge-m3）。
     *
     * @return 写入 Milvus 的文本片段数量
     */
    @GetMapping("/ingest")
    public Result<Integer> ingest() throws IOException {
        int count = knowledgeIngester.ingest();
        return Result.success(count);
    }

    /**
     * RAG 问答接口：先从 Milvus 经"topK={@value #RAG_TOP_K} 召回 + 相似度阈值
     * {@value #RAG_SIMILARITY_THRESHOLD} 过滤"拿到相关片段，
     * 拼装 Prompt 后调用推理模型输出精准答案，避免大模型幻觉。
     *
     * @param message 用户提问
     * @return 基于知识库内容的精准答案
     */
    @GetMapping("/rag")
    public Result<String> rag(@RequestParam String message) {
        RAGContext context = retrieveContext(message);
        // 检索无结果时直接短路返回，不浪费一次模型调用
        if (context == null) {
            return Result.success("知识库中未找到相关信息");
        }
        metricsService.recordRAGRetrieval(true, context.chunkCount(), context.retrievalMs());
        long aiStart = System.currentTimeMillis();
        String answer = deepseekChatClient.prompt()
                .system(RAG_SYSTEM_PROMPT)
                .user(ragUserPrompt(context.text(), message))
                .call()
                .content();
        metricsService.recordAIRequest(reasoningModel, "/api/ai/rag", System.currentTimeMillis() - aiStart);
        return Result.success(answer);
    }

    /**
     * RAG 流式问答接口：检索逻辑与 {@link #rag} 一致，但以 SSE 逐段推送模型输出，
     * 避免用户在推理模型生成期间长时间等待空白页面。
     *
     * @param message 用户提问
     * @return SSE 事件流（最终回答，按序拼接即为完整回复）
     */
    @GetMapping(value = "/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ragStream(@RequestParam String message, HttpServletResponse response) {
        // 提前声明响应编码，让 Tomcat 提交响应头时追加 charset=UTF-8，避免浏览器中文乱码
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        RAGContext context = retrieveContext(message);
        if (context == null) {
            return Flux.just("知识库中未找到相关信息");
        }
        metricsService.recordRAGRetrieval(true, context.chunkCount(), context.retrievalMs());
        long aiStart = System.currentTimeMillis();
        return deepseekChatClient.prompt()
                .system(RAG_SYSTEM_PROMPT)
                .user(ragUserPrompt(context.text(), message))
                .stream()
                .content()
                // 流式输出成功完成时补记一次 AI 调用耗时（取消/错误不计）
                .doOnComplete(() -> metricsService.recordAIRequest(reasoningModel,
                        "/api/ai/rag/stream", System.currentTimeMillis() - aiStart))
                .onErrorResume(e -> Flux.just("[ERROR] " + e.getMessage()));
    }

    /** RAG 系统 Prompt：强约束基于知识库作答并注明来源，抑制幻觉。 */
    private static final String RAG_SYSTEM_PROMPT = "你是一个严谨的企业知识库问答助手。回答规则："
            + "1) 必须严格依据下方给出的知识库内容作答；"
            + "2) 若知识库内容不足以回答问题，必须回答“知识库中未找到相关信息”，严禁编造或臆测；"
            + "3) 回答末尾另起一行，以“来源：xxx”注明所参考片段的【来源】标注；"
            + "4) 回答要简洁准确。";

    /**
     * 从 Milvus 检索与问题相关的文本片段并拼装上下文（每片携带来源标注）。
     * 两道闸门串联：先取 COSINE 排序后的前 {@value #RAG_TOP_K} 个候选，
     * 再丢弃相似度低于 {@value #RAG_SIMILARITY_THRESHOLD} 的片段。
     * 未命中时直接记录 miss 指标，命中时由调用方记录 hit（需区分同步/流式端点）。
     *
     * @return 检索结果（上下文文本、片段数、检索耗时）；无片段通过阈值时返回 null
     */
    private RAGContext retrieveContext(String message) {
        long start = System.currentTimeMillis();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(message)
                        .topK(RAG_TOP_K)
                        .similarityThreshold(RAG_SIMILARITY_THRESHOLD)
                        .build());
        long retrievalMs = System.currentTimeMillis() - start;
        if (docs == null || docs.isEmpty()) {
            log.info("[RAG] 无高于阈值 {} 的片段，问题：{}", RAG_SIMILARITY_THRESHOLD, message);
            metricsService.recordRAGRetrieval(false, 0, retrievalMs);
            return null;
        }
        // 打印每条命中的 COSINE 分数，用于观察"该命中/不该命中"问题的分数断层以校准阈值
        docs.forEach(doc -> log.info("[RAG] 命中片段 score={} source={}",
                doc.getScore(), doc.getMetadata().getOrDefault("source", "unknown")));
        String text = docs.stream()
                .map(doc -> {
                    String source = String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"));
                    return "【来源: " + source + "】\n" + doc.getText();
                })
                .collect(Collectors.joining("\n\n---\n\n"));
        return new RAGContext(text, docs.size(), retrievalMs);
    }

    /** RAG 检索结果：拼装好的上下文文本、通过阈值的片段数、检索耗时（毫秒） */
    private record RAGContext(String text, int chunkCount, long retrievalMs) {
    }

    /** 拼装 RAG 用户 Prompt：知识库上下文 + 用户问题。 */
    private String ragUserPrompt(String context, String message) {
        return "知识库内容：\n" + context + "\n\n请根据以上知识库内容回答问题：" + message;
    }
}
