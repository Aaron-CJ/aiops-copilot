package com.aiops.aiopscopilot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring 上下文冒烟测试：验证所有 Bean（三个 ChatClient、Milvus VectorStore、
 * OpsScheduler、DiagnosisService 等）的装配关系成立——例如多构造器上 @Autowired 选错、
 * @Qualifier 漏标都会在这里暴露（历史上确实抓到过一次）。
 * <p>
 * ChatClient 构建与 Milvus 客户端均为惰性连接，不发起真实 RPC，
 * 因此 Ollama/Milvus 离线时本测试仍可通过；真正调用模型/向量库的端到端验证需手动启动依赖。
 */
@SpringBootTest
class AiopsCopilotApplicationTests {

    @Test
    void contextLoads() {
    }

}
