package com.aiops.aiopscopilot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aiops.aiopscopilot.tool.PrometheusTool;
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

    /**
     * 第三个 ChatClient Bean：智能运维巡检专用 Agent。
     * <p>
     * 设计要点：
     * 1) 模型选 deepseek-r1:8b（推理能力强，适合做异常判断 + 根因分析）；
     * 2) 注册 {@link PrometheusTool}（时序数据眼睛）+ {@link SystemHealthTools}（瞬时本地状态）双引擎；
     * 3) defaultSystem 设置 AIOps 巡检员人设 + 严禁编造数值；
     * 4) 不复用 {@code deepseekChatClient}：避免巡检专属 prompt 和工具 schema 污染通用对话场景的 token 消耗。
     * <p>
     * 同时服务两个场景：
     * <ul>
     *   <li>被动：{@link com.aiops.aiopscopilot.controller.AgentController} 的 /api/agent/ops</li>
     *   <li>主动：{@link com.aiops.aiopscopilot.service.OpsScheduler} 的定时巡检</li>
     * </ul>
     */
    @Bean
    public ChatClient opsAgentClient(ChatClient.Builder builder,
                                     PrometheusTool prometheusTool,
                                     SystemHealthTools systemHealthTools) {
        return builder
                .defaultOptions(OllamaChatOptions.builder().model("deepseek-r1:8b"))
                .defaultTools(prometheusTool, systemHealthTools)
                .defaultSystem("你是一名 AIOps 智能运维 Agent。面对运维问题或巡检任务，"
                        + "应主动调用工具获取真实指标后再作答，严禁凭空编造数值。"
                        + "输出格式严格遵循用户指定的 JSON 结构，不要加任何额外解释文字。")
                .build();
    }
}
