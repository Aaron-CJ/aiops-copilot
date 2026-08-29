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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private final ChatClient deepseekChatClient;
    private final VectorStore vectorStore;
    private final KnowledgeIngester knowledgeIngester;

    public AiChatController(@Qualifier("deepseekChatClient") ChatClient deepseekChatClient,
                           VectorStore vectorStore,
                           KnowledgeIngester knowledgeIngester) {
        this.deepseekChatClient = deepseekChatClient;
        this.vectorStore = vectorStore;
        this.knowledgeIngester = knowledgeIngester;
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
     * 需先在本地启动 Milvus（docker）并拉取 Ollama embedding 模型（ollama pull nomic-embed-text）。
     *
     * @return 写入 Milvus 的文本片段数量
     */
    @GetMapping("/ingest")
    public Result<Integer> ingest() throws IOException {
        int count = knowledgeIngester.ingest();
        return Result.success(count);
    }

    /**
     * RAG 问答接口：先从 Milvus 检索最相关的 3 个文本片段，
     * 拼装 Prompt 后调用 deepseekChatClient 输出精准答案，避免大模型幻觉。
     *
     * @param message 用户提问
     * @return 基于知识库内容的精准答案
     */
    @GetMapping("/rag")
    public Result<String> rag(@RequestParam String message) {
        String context = retrieveContext(message);
        // 检索无结果时直接短路返回，不浪费一次模型调用
        if (context == null) {
            return Result.success("知识库中未找到相关信息");
        }
        String answer = deepseekChatClient.prompt()
                .system(RAG_SYSTEM_PROMPT)
                .user(ragUserPrompt(context, message))
                .call()
                .content();
        return Result.success(answer);
    }

    /**
     * RAG 流式问答接口：检索逻辑与 {@link #rag} 一致，但以 SSE 逐段推送模型输出，
     * 避免用户在 deepseek-r1 生成期间长时间等待空白页面。
     *
     * @param message 用户提问
     * @return SSE 事件流（最终回答，按序拼接即为完整回复）
     */
    @GetMapping(value = "/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ragStream(@RequestParam String message, HttpServletResponse response) {
        // 提前声明响应编码，让 Tomcat 提交响应头时追加 charset=UTF-8，避免浏览器中文乱码
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String context = retrieveContext(message);
        if (context == null) {
            return Flux.just("知识库中未找到相关信息");
        }
        return deepseekChatClient.prompt()
                .system(RAG_SYSTEM_PROMPT)
                .user(ragUserPrompt(context, message))
                .stream()
                .content()
                .onErrorResume(e -> Flux.just("[ERROR] " + e.getMessage()));
    }

    /** RAG 系统 Prompt：强约束基于知识库作答并注明来源，抑制幻觉。 */
    private static final String RAG_SYSTEM_PROMPT = "你是一个严谨的企业知识库问答助手。回答规则："
            + "1) 必须严格依据下方给出的知识库内容作答；"
            + "2) 若知识库内容不足以回答问题，必须回答“知识库中未找到相关信息”，严禁编造或臆测；"
            + "3) 回答末尾另起一行，以“来源：xxx”注明所参考片段的【来源】标注；"
            + "4) 回答要简洁准确。";

    /**
     * 从 Milvus 检索与问题最相关的 3 个文本片段并拼装上下文（每片携带来源标注）。
     *
     * @return 上下文文本；检索无结果时返回 null，交由调用方短路处理
     */
    private String retrieveContext(String message) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(message).topK(3).build());
        if (docs == null || docs.isEmpty()) {
            return null;
        }
        return docs.stream()
                .map(doc -> {
                    String source = String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"));
                    return "【来源: " + source + "】\n" + doc.getText();
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /** 拼装 RAG 用户 Prompt：知识库上下文 + 用户问题。 */
    private String ragUserPrompt(String context, String message) {
        return "知识库内容：\n" + context + "\n\n请根据以上知识库内容回答问题：" + message;
    }
}
