package com.aiops.aiopscopilot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring 上下文冒烟测试：验证所有 Bean（三个 ChatClient、Milvus VectorStore、
 * OpsScheduler、DiagnosisService 等）的装配关系成立——例如多构造器上 @Autowired 选错、
 * @Qualifier 漏标都会在这里暴露（历史上确实抓到过一次）。
 * <p>
 * 前置条件：Milvus 必须可达。Milvus SDK 的 MilvusServiceClient 在 bean 创建阶段就会
 * 建立 gRPC 连接（wait_for_ready，约 10s deadline），Milvus 离线时上下文直接启动失败
 * （2026-09-23 实测：Connection refused localhost:19530 → DEADLINE_EXCEEDED）；
 * Ollama 侧是惰性 WebClient，离线不影响装配。跑本测试前先启动
 * docker/milvus-standalone-docker-compose.yml。真正调用模型/向量库的端到端验证仍需手动起齐依赖。
 */
@SpringBootTest
class AiopsCopilotApplicationTests {

    @Test
    void contextLoads() {
    }

}
