package com.aiops.aiopscopilot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 模型 Bean 配置
 */
@Configuration
public class AiConfig {

    /**
     * 构建 ChatClient：Builder 已按 application.yml 的 ollama 配置自动装配
     * （chat 模型 deepseek-r1:8b、base-url 等）。
     * 显式命名为 deepseekChatClient，将来出现多个 ChatClient Bean 时
     * 可用 @Qualifier 精确注入（见 AiChatController / ChatService 的用法）。
     */
    @Bean
    public ChatClient deepseekChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
