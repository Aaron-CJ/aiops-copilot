package com.aiops.aiopscopilot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    /**
     * 基于 application.yml 中的 Ollama 配置构建 ChatClient
     */
    @Bean
    public ChatClient deepseekChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
