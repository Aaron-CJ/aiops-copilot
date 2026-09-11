package com.aiops.aiopscopilot.controller;

import com.aiops.aiopscopilot.common.result.Result;
import com.aiops.aiopscopilot.service.MetricsService;
import com.aiops.aiopscopilot.tool.PrometheusTool;
import com.aiops.aiopscopilot.tool.SystemHealthTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能运维 Agent 接口（被动响应）：基于 qwen3:8b + Spring AI Function Calling。
 * <p>
 * 与 RAG 接口的区别：RAG 是"检索知识库 + 生成答案"，
 * Agent 是"模型自主调用工具拿到真实数据 + 生成答案"——本质都是给 LLM 接外部能力。
 * <p>
 * 与 {@link com.aiops.aiopscopilot.service.OpsScheduler}（主动巡检）的区别：
 * 本接口是"用户问才查"，调度器是"每分钟自己查"。两者模型统一为 qwen3:8b
 * （本接口走 opsAgentClient 带工具 schema，调度器走 qwenChatClient 无需工具），
 * 避免 Ollama 单模型驻留下两模型交替触发反复换载。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ChatClient opsAgentClient;
    private final MetricsService metricsService;
    private final String fastModel;

    public AgentController(@Qualifier("opsAgentClient") ChatClient opsAgentClient,
                           MetricsService metricsService,
                           @Value("${aiops.models.fast}") String fastModel) {
        this.opsAgentClient = opsAgentClient;
        this.metricsService = metricsService;
        this.fastModel = fastModel;
    }

    /**
     * 服务器健康检查 Agent：用户用自然语言提问，由模型（qwen3:8b）自主决定调用哪些工具。
     * <p>
     * 可用工具：
     * <ul>
     *   <li>{@link SystemHealthTools#getServerHealth()} — 瞬时 CPU/内存（本地 MXBean）</li>
     *   <li>{@link PrometheusTool#queryMetric(String)} — 时序指标（Prometheus 历史 QPS/GC/线程数）</li>
     * </ul>
     * 调用链：用户问题 → qwen3:8b 分析 → Function Calling 拿真实指标 → 模型生成评估 → 返回。
     *
     * @param message 用户提问，默认请求一次综合健康检查
     * @return 模型生成的健康评估文本
     */
    @GetMapping("/ops")
    public Result<String> ops(@RequestParam(defaultValue = "请检查当前服务器健康状态，包括 CPU 占用率和内存剩余，并给出简短评估。") String message) {
        // opsAgentClient 已通过 defaultSystem 注入 AIOps 人设，这里不再重复 .system()
        long start = System.currentTimeMillis();
        String reply = opsAgentClient.prompt()
                .user(message)
                .call()
                .content();
        metricsService.recordAIRequest(fastModel, "/api/agent/ops", System.currentTimeMillis() - start);
        return Result.success(reply);
    }
}
