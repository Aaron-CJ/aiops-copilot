package com.aiops.aiopscopilot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aiops.aiopscopilot.tool.SystemHealthTools;

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

    /**
     * 第二个 ChatClient Bean：连接本地 Ollama 的 qwen2.5 模型。
     * <p>
     * 实现要点：
     * 1) {@code defaultOptions} 覆盖默认模型为 qwen2.5:7b（同一个 Ollama 服务，模型按请求切换）；
     * 2) {@code defaultTools} 把 {@link SystemHealthTools} 注册为可用工具，
     *    Spring AI 会扫描其中带 {@link Tool @Tool} 注解的方法，
     *    把方法名与描述暴露给大模型，模型自主决定何时调用——即 Function Calling。
     */
    @Bean
    public ChatClient qwenChatClient(ChatClient.Builder builder, SystemHealthTools systemHealthTools) {
        return builder
                .defaultOptions(OllamaChatOptions.builder().model("qwen2.5:7b"))
                .defaultTools(systemHealthTools)
                .build();
    }
}
