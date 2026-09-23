package com.aiops.aiopscopilot.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * 知识库初始化器：读取 knowledge.txt → 文本切片（Text Chunking）→ Embedding 向量化 → 写入 Milvus。
 * <p>
 * <b>文档标题约定</b>：knowledge.txt 的首个非空行若写成「知识库：文档标题」，
 * 标题会被提取为每个片段的 source 元数据（RAG 回答末尾的"来源：xxx"即引用它），
 * 并从切片正文中剥离——避免同一份文档里"文件名"和"正文标题"两个来源候选
 * 导致模型在流式/非流式回答中引用不一致。首个非空行不符合该约定时，
 * source 兜底为文件名 knowledge.txt。
 */
@Service
public class KnowledgeIngester {

    /** 知识文件名（作为无标题文档的兜底来源名，也是历史数据的旧 source 值） */
    private static final String FALLBACK_SOURCE = "knowledge.txt";

    /** 标题行正则：捕获「知识库：」之后的标题文本（冒号兼容中文：与英文:） */
    private static final Pattern TITLE_LINE = Pattern.compile("^知识库[：:]\\s*(.+)\\s*$");

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;

    public KnowledgeIngester(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        // 基于 Token 的文本切片器：400 token/片段，比默认 800 更细粒度，语义检索更精准
        // 注：Spring AI 2.0 的 Builder API 未提供 chunkOverlap 配置项
        this.textSplitter = TokenTextSplitter.builder()
                .withChunkSize(400)
                .build();
    }

    /**
     * 执行一次知识库摄入（标题约定见类注释），重复调用幂等。
     *
     * @return 实际写入 Milvus 的文本片段数量
     * @throws IOException 知识文件读取失败，或标题含引号（无法安全构建 Milvus 过滤表达式）
     */
    public int ingest() throws IOException {
        Resource resource = new ClassPathResource(FALLBACK_SOURCE);
        String rawContent;
        // StreamUtils.copyToString 不会关闭流，必须 try-with-resources，否则每次摄入泄漏一个文件句柄
        try (InputStream in = resource.getInputStream()) {
            rawContent = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }

        ResolvedSource resolved = resolveSourceAndBody(rawContent);
        String source = resolved.source();
        String content = resolved.content();

        // —— 防重删除（保证重复 ingest 幂等）——
        // 本次写入的片段 source 是提取出的标题，先删同名旧片段；
        // 标题方案上线前灌入的历史片段 source 是文件名 knowledge.txt，若两者不同再补删一次，
        // 覆盖"旧版数据 → 新版数据"的迁移场景，防止新旧两套片段并存导致重复召回。
        vectorStore.delete("source == '" + source + "'");
        if (!FALLBACK_SOURCE.equals(source)) {
            vectorStore.delete("source == '" + FALLBACK_SOURCE + "'");
        }

        // 将整篇知识文本包装为单个 Document，携带来源元数据（切片后每片都会继承）
        Document document = new Document(content, Map.of("source", source));

        List<Document> chunks = textSplitter.apply(List.of(document));
        vectorStore.add(chunks);

        return chunks.size();
    }

    /** 标题提取结果：source 为来源名（无标题文档兜底文件名），content 为剥离标题行后的正文 */
    record ResolvedSource(String source, String content) {
    }

    /**
     * 标题提取状态机（纯函数，便于单测全部分支）：
     * 只检查"首个非空行"——命中「知识库：标题」约定则提取为 source 并从正文剥离，
     * 首个非空行不是标题则认定整篇无标题，正文中再出现"知识库："也不误判。
     *
     * @throws IOException 标题含引号（" 或 '）——source 会拼进 Milvus 删除表达式的
     *                     字符串字面量，含引号会破坏表达式，拒绝而不是静默转义
     */
    static ResolvedSource resolveSourceAndBody(String rawContent) throws IOException {
        String source = FALLBACK_SOURCE;
        StringBuilder body = new StringBuilder();
        boolean titleResolved = false;
        for (String line : rawContent.split("\\R", -1)) {
            if (!titleResolved) {
                Matcher matcher = TITLE_LINE.matcher(line);
                if (matcher.matches()) {
                    String title = matcher.group(1).trim();
                    if (title.contains("'") || title.contains("\"")) {
                        throw new IOException("知识库标题含引号（\" 或 '），无法安全构建 Milvus 过滤表达式，请修改标题: " + title);
                    }
                    source = title;
                    titleResolved = true;
                    continue;
                }
                if (!line.isBlank()) {
                    // 首个非空行不是标题：认定文档无标题，关闭匹配；该行是正文，下面照常追加
                    titleResolved = true;
                }
                // 标题之前的空行：开关保持 false，继续向下寻找首个非空行
            }
            body.append(line).append('\n');
        }
        // 标题行被抽走后文首可能残留空行，统一 strip 掉
        return new ResolvedSource(source, body.toString().strip());
    }
}
