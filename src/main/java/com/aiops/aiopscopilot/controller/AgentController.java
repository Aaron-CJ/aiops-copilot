package com.aiops.aiopscopilot.controller;

import com.aiops.aiopscopilot.common.result.Result;
import com.aiops.aiopscopilot.tool.SystemHealthTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能运维 Agent 接口：基于 qwen2.5:14b + Spring AI Function Calling。
 * <p>
 * 与 RAG 接口的区别：RAG 是"检索知识库 + 生成答案"，
 * Agent 是"模型自主调用工具拿到真实数据 + 生成答案"——本质都是给 LLM 接外部能力。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ChatClient qwenChatClient;

    public AgentController(@Qualifier("qwenChatClient") ChatClient qwenChatClient) {
        this.qwenChatClient = qwenChatClient;
    }

    /**
     * 服务器健康检查 Agent：用户用自然语言提问，
     * 由 qwen 自主决定调用 {@link SystemHealthTools#getServerHealth()} 获取真实指标后作答。
     * <p>
     * 调用链：用户问题 → qwen 分析 → Function Calling 调用 getServerHealth()
     * → 拿到 CPU/内存真实值 → 模型基于真实值生成评估 → 返回。
     *
     * @param message 用户提问，默认请求一次综合健康检查
     * @return 模型生成的健康评估文本
     */
    @GetMapping("/ops")
    public Result<String> ops(@RequestParam(defaultValue = "请检查当前服务器健康状态，包括 CPU 占用率和内存剩余，并给出简短评估。") String message) {
        String reply = qwenChatClient.prompt()
                .system("你是一名 AIOps 智能运维助手。"
                        + "面对用户的运维问题，应主动调用可用的工具获取真实指标后再作答，"
                        + "严禁凭空编造数值。回答简洁明了。")
                .user(message)
                .call()
                .content();
        return Result.success(reply);
    }
}
