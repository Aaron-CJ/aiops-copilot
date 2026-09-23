package com.aiops.aiopscopilot.service;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * KnowledgeIngester 测试：
 * <ul>
 *   <li>{@code resolveSourceAndBody} 是包级私有纯函数，同包测试直接调用，
 *       覆盖标题提取状态机的全部分支（含引号拒绝——source 会拼进 Milvus 表达式字面量，
 *       含引号会破坏表达式，生产代码选择抛异常而非静默转义）；</li>
 *   <li>{@code ingest()} 用 Mockito mock VectorStore 跑一遍真实 classpath 知识文件，
 *       验证"防重双删 + 切片写入 + source 元数据"接线正确。切片器是本地 JTokkit，不依赖任何服务。</li>
 * </ul>
 */
class KnowledgeIngesterTest {

    // ==================== resolveSourceAndBody 纯函数分支 ====================

    /** 标准标题行：提取为 source，并从正文中剥离 */
    @Test
    void titleLineExtractedAsSourceAndStrippedFromBody() throws IOException {
        KnowledgeIngester.ResolvedSource r =
                KnowledgeIngester.resolveSourceAndBody("知识库：运维手册\n\n正文第一条\n正文第二条");

        assertEquals("运维手册", r.source());
        assertEquals("正文第一条\n正文第二条", r.content(), "标题行与文首空行应被剥离");
        assertFalse(r.content().contains("知识库："), "标题行不得残留在切片正文里");
    }

    /** 无标题文档：source 兜底为文件名，正文原样保留 */
    @Test
    void noTitleFallsBackToFileName() throws IOException {
        KnowledgeIngester.ResolvedSource r =
                KnowledgeIngester.resolveSourceAndBody("正文第一行\n知识库：这行在正文里，不是标题");

        assertEquals("knowledge.txt", r.source());
        assertTrue(r.content().startsWith("正文第一行"));
        assertTrue(r.content().contains("知识库：这行在正文里，不是标题"),
                "首个非空行不是标题后，正文中再出现的约定文本不得被误判为标题");
    }

    /** 标题前允许有任意空行：跳过空行继续找首个非空行 */
    @Test
    void leadingBlankLinesThenTitleStillExtracted() throws IOException {
        KnowledgeIngester.ResolvedSource r =
                KnowledgeIngester.resolveSourceAndBody("\n\n  \n知识库：X手册\n正文");

        assertEquals("X手册", r.source());
        assertEquals("正文", r.content());
    }

    /** 英文冒号 + 标题首尾空白：冒号兼容 :，标题做 trim */
    @Test
    void englishColonAndSurroundingSpacesAreTrimmed() throws IOException {
        KnowledgeIngester.ResolvedSource r =
                KnowledgeIngester.resolveSourceAndBody("知识库:   运维手册 V2   \n正文");

        assertEquals("运维手册 V2", r.source());
        assertEquals("正文", r.content());
    }

    /** 单引号标题：抛 IOException（引号会截断 Milvus 过滤表达式的字符串字面量） */
    @Test
    void singleQuoteInTitleRejected() {
        IOException ex = assertThrows(IOException.class,
                () -> KnowledgeIngester.resolveSourceAndBody("知识库：坏'标题\n正文"));
        assertTrue(ex.getMessage().contains("引号"), "异常信息应指向引号问题: " + ex.getMessage());
    }

    /** 双引号标题：同样拒绝 */
    @Test
    void doubleQuoteInTitleRejected() {
        assertThrows(IOException.class,
                () -> KnowledgeIngester.resolveSourceAndBody("知识库：坏\"标题\n正文"));
    }

    /** 空内容（极端输入）：兜底 source，不抛异常 */
    @Test
    void emptyContentYieldsFileNameSource() throws IOException {
        KnowledgeIngester.ResolvedSource r = KnowledgeIngester.resolveSourceAndBody("");

        assertEquals("knowledge.txt", r.source());
        assertEquals("", r.content());
    }

    // ==================== ingest() 接线（mock VectorStore + 真实 classpath 文件） ====================

    /**
     * 真实 knowledge.txt 首行是「知识库：CloudWeaver 内部行政规定」：
     * 写入前必须先按新 source 删除一次、再补删旧文件名 source（历史数据迁移场景），
     * 写入的每个切片都携带标题 source 元数据。
     */
    @Test
    @SuppressWarnings("unchecked")
    void ingestDeletesBothSourcesAndWritesTitleTaggedChunks() throws IOException {
        VectorStore vectorStore = mock(VectorStore.class);
        KnowledgeIngester ingester = new KnowledgeIngester(vectorStore);

        int chunks = ingester.ingest();

        verify(vectorStore, times(1)).delete("source == 'CloudWeaver 内部行政规定'");
        verify(vectorStore, times(1)).delete("source == 'knowledge.txt'");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).add(captor.capture());
        List<Document> added = captor.getValue();
        assertEquals(chunks, added.size(), "返回值应等于实际写入切片数");
        assertFalse(added.isEmpty(), "语料至少应切出 1 个片段");
        assertTrue(added.stream().allMatch(d ->
                "CloudWeaver 内部行政规定".equals(d.getMetadata().get("source"))),
                "每个切片必须继承标题 source，RAG 引用才会一致");
        assertTrue(added.stream().noneMatch(d -> d.getText().contains("知识库：CloudWeaver")),
                "标题行不应进入任何切片正文");
    }
}
