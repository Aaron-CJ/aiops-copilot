package com.aiops.aiopscopilot.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
     *
     * @return 实际写入 Milvus 的文本片段数量
     */
    public int ingest() throws IOException {
        Resource resource = new ClassPathResource("knowledge.txt");
        String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

        // 防重：先按 source 元数据删除旧片段，保证重复 ingest 幂等（切片会继承元数据，每片都带 source）
        vectorStore.delete("source == 'knowledge.txt'");

        // 将整篇知识文本包装为单个 Document，携带来源元数据
        Document document = new Document(content, Map.of("source", "knowledge.txt"));

        // 文本切片（Text Chunking）
        List<Document> chunks = textSplitter.apply(List.of(document));

        // VectorStore.add 内部会调用 EmbeddingModel 将每个片段转向量并写入 Milvus
        vectorStore.add(chunks);

        return chunks.size();
    }
}
