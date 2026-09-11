package com.aiops.aiopscopilot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
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
     * 第二个 ChatClient Bean：连接本地 Ollama 的 qwen3 模型（供 OpsScheduler 定时巡检使用）。
     * <p>
     * 实现要点：
     * 1) {@code defaultOptions} 覆盖默认模型为 qwen3:8b 并 disableThinking()
     *    （qwen3 默认开思考链，巡检只要结构化结论，关闭后响应快数倍）；
     * 2) <b>刻意不注册任何工具</b>：巡检场景下指标由调度器通过
     *    {@code PrometheusTool.queryFixedMetrics()} 预拉后直接塞进 Prompt，
     *    模型只负责"看快照→判异常→输出 JSON"。挂工具 schema 会让每次巡检平白多带
     *    几百 token 的工具描述，还可能诱导模型在巡检中发起多余的工具调用往返。
     *    工具能力只挂在 {@link #opsAgentClient}（交互问答需要模型自主查数）上。
     */
    @Bean
    public ChatClient qwenChatClient(ChatClient.Builder builder,
                                     @Value("${aiops.models.fast}") String fastModel) {
        return builder
                // qwen3 为混合思考模型，默认开启思考链；巡检场景追求快，显式关闭
                .defaultOptions(OllamaChatOptions.builder()
                        .model(fastModel)
                        .disableThinking())
                .build();
    }

    /**
     * 第三个 ChatClient Bean：智能运维交互问答 Agent（/api/agent/ops 专用）。
     * <p>
     * 设计要点：
     * 1) 模型选 qwen3:8b（关闭思考）而非 deepseek-r1:8b——r1 在纯 CPU 推理下思考链过长，
     *    单次工具调用链路实测 2 分钟以上（curl 120 秒超时），同步 HTTP 接口无法接受；
     *    qwen3 同样是混合思考模型，但通过 disableThinking() 关闭思考链后直出答案，
     *    Function Calling 成熟可靠；巡检与交互统一用同一模型还能避免
     *    Ollama 单模型驻留时两模型反复"卸载→重载"（每次换载 10-30 秒）；
     * 2) 注册 {@link PrometheusTool}（时序数据眼睛）+ {@link SystemHealthTools}（瞬时本地状态）双引擎，
     *    交互场景模型需要自主决定调用哪个工具，工具 schema 必须挂在本 Bean 上；
     * 3) defaultSystem 强调"必须调工具拿真实数据"——这是防幻觉的关键：
     *    曾经模型没有 PrometheusTool 可用时，把 QPS 凭空编造成了 450；
     * 4) 输出自然语言而非严格 JSON：旧版要求 JSON 是因为调度器要程序化解析，
     *    现在巡检已改走 qwenChatClient（数据由调度器预拉塞 Prompt），本 Bean 只服务交互问答。
     * <p>
     * 巡检为什么不用本 Bean：巡检每分钟一次且不需要模型自主查数据
     * （{@link PrometheusTool#queryFixedMetrics} 由调度器直调），无需工具 schema，
     * 注入轻量的 qwenChatClient 更省 token、延迟更低。
     */
    @Bean
    public ChatClient opsAgentClient(ChatClient.Builder builder,
                                     @Value("${aiops.models.fast}") String fastModel,
                                     PrometheusTool prometheusTool,
                                     SystemHealthTools systemHealthTools) {
        return builder
                // 快速通道模型关闭思考链：工具调用/解读场景无需深度推理，直出答案更快
                .defaultOptions(OllamaChatOptions.builder()
                        .model(fastModel)
                        .disableThinking())
                .defaultTools(prometheusTool, systemHealthTools)
                .defaultSystem("你是一名 AIOps 智能运维 Agent。面对运维问题，"
                        + "必须先调用工具获取真实指标，再基于工具返回的数据作答，严禁凭空编造数值。"
                        + "用简洁的中文自然语言给出结论、依据和建议，不要用 JSON 包裹回答。"
                        + "涉及数值换算时（如 QPS 次/秒换算为次/分钟、字节换算为 GB 等），"
                        + "必须逐步展示计算过程，直接给出换算结果，避免量级错误。"
                        + "本系统指标由 Spring Boot Actuator + Micrometer 暴露，调用 queryMetric 时"
                        + "必须使用以下真实指标名，严禁凭记忆使用 http_requests_total 等本系统不存在的指标：\n"
                        + "- QPS（每秒请求数）：rate(http_server_requests_seconds_count[1m])\n"
                        + "- 每分钟请求量：increase(http_server_requests_seconds_count[1m])\n"
                        + "- 某接口 QPS：rate(http_server_requests_seconds_count{uri=\"/api/xxx\"}[1m])\n"
                        + "- 请求最大延迟（秒）：http_server_requests_seconds_max\n"
                        + "- JVM 堆内存（字节，按 id 分代 Eden/Survivor/Old）：jvm_memory_used_bytes{area=\"heap\"}\n"
                        + "- 进程 CPU 使用率（0~1）：process_cpu_usage\n"
                        + "- 阻塞线程数：jvm_threads_states_threads{state=\"blocked\"}\n"
                        + "- 近 5 分钟 GC 次数：increase(jvm_gc_pause_seconds_count[5m])\n"
                        + "若查询返回 count=0，通常是指标名写错，应改用上面的正确指标名重试。")
                .build();
    }
}
