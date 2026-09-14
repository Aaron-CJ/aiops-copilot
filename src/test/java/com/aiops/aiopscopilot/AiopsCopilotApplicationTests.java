package com.aiops.aiopscopilot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring 上下文冒烟测试：验证所有 Bean（含三个 ChatClient、VectorStore、调度器）能正常装配。
 * <p>
 * 运行前置：本地 Ollama 与 Milvus 需已启动，否则上下文初始化会因连接失败而报错。
 */
@SpringBootTest
class AiopsCopilotApplicationTests {

    @Test
    void contextLoads() {
    }

}
