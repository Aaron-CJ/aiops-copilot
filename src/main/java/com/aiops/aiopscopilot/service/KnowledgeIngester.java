package com.aiops.aiopscopilot.service;

import java.io.IOException;
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
 * 知识库初始化器：读取 knowledge.txt → 文本切片（Text Chunking）→ 调用 Embedding 模型 → 写入 Milvus
 * <p>
 * 调用 {@link #ingest()} 时，VectorStore 内部会自动调用配置的 Ollama Embedding 模型
 * 将每个文本片段转向量后写入 Milvus。
 */
@Service
public class KnowledgeIngester {

    /** 知识文件名（作为无标题文档的兜底来源名，也是历史数据的旧 source 值） */
    private static final String FALLBACK_SOURCE = "knowledge.txt";

    /**
     * 文档标题约定：knowledge.txt 首个非空行形如「知识库：CloudWeaver 内部行政规定」，
     * 冒号后内容即人类可读的文档标题，用作每个片段的 source 元数据。
     * 企业知识库场景下引用文档标题比引用文件名更有意义。
     */
    private static final Pattern TITLE_LINE = Pattern.compile("^知识库[：:]\\s*(.+)\\s*$");

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;

    public KnowledgeIngester(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        // 基于 Token 的文本切片器：400 token/片段，比默认 800 更细粒度，
        // 语义检索更精准，也远离 Embedding 模型的上下文上限（2.0 版不支持 chunkOverlap）
        this.textSplitter = TokenTextSplitter.builder()
                .withChunkSize(400)
                .build();
    }

    /**
     * 读取 classpath 下的 knowledge.txt，进行文本切片后，
     * 调用 Ollama Embedding 模型转成向量并写入 Milvus。
     * <p>
     * 首个非空行若符合「知识库：标题」约定，标题会被提取为 source 元数据并从正文中剥离；
     * 不符合约定时 source 兜底为文件名 knowledge.txt。
     *
     * @return 实际写入 Milvus 的文本片段数量
     */
    public int ingest() throws IOException {
        Resource resource = new ClassPathResource(FALLBACK_SOURCE);
        String rawContent = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

        // 提取文档标题作为来源名，并将标题行从待切片正文中剥离
        String source = FALLBACK_SOURCE;
        StringBuilder body = new StringBuilder();
        boolean titleResolved = false;
        for (String line : rawContent.split("\\R", -1)) {
            if (!titleResolved) {
                Matcher matcher = TITLE_LINE.matcher(line);
                if (matcher.matches()) {
                    source = matcher.group(1).trim();
                    titleResolved = true;
                    continue;
                }
                if (!line.isBlank()) {
                    // 首个非空行不是标题约定，后续不再尝试匹配
                    titleResolved = true;
                }
            }
            body.append(line).append('\n');
        }
        String content = body.toString().strip();

        // 防重：删除同一文档的旧片段（按本次标题）与历史文件名来源的旧片段，保证重复 ingest 幂等
        vectorStore.delete("source == '" + source + "'");
        if (!FALLBACK_SOURCE.equals(source)) {
            vectorStore.delete("source == '" + FALLBACK_SOURCE + "'");
        }

        // 将整篇知识文本包装为单个 Document，携带来源元数据（切片后每片都会继承）
        Document document = new Document(content, Map.of("source", source));

        // 文本切片（Text Chunking）
        List<Document> chunks = textSplitter.apply(List.of(document));

        // VectorStore.add 内部会调用 EmbeddingModel 将每个片段转向量并写入 Milvus
        vectorStore.add(chunks);

        return chunks.size();
    }
}
