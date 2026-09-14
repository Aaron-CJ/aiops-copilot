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
 * 智能运维 Agent 接口（被动响应）：模型通过 Spring AI Function Calling 自主调工具取数作答。
 * <p>
 * 与 RAG 接口的区别：RAG 检索知识库生成答案，本接口调工具拿真实指标生成答案。
 * 与 {@link com.aiops.aiopscopilot.service.OpsScheduler}（主动巡检）的区别：
 * 本接口"用户问才查"（opsAgentClient，挂工具 schema），调度器"每分钟自己查"（qwenChatClient，不挂工具）。
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
     * 服务器健康检查 Agent：用户用自然语言提问，由模型自主决定调用哪些工具
     * （{@link SystemHealthTools#getServerHealth()} 瞬时本地状态、
     * {@link PrometheusTool#queryMetric(String)} 时序指标），拿到真实数据后生成评估。
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
