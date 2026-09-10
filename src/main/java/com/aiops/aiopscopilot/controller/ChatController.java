package com.aiops.aiopscopilot.controller;

import com.aiops.aiopscopilot.common.result.Result;
import com.aiops.aiopscopilot.service.MetricsService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 基础对话接口：一次性返回完整回复（非流式）。
 * SSE 流式版本与 RAG 问答见 {@link AiChatController}。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient deepseekChatClient;
    private final MetricsService metricsService;

    public ChatController(ChatClient deepseekChatClient, MetricsService metricsService) {
        this.deepseekChatClient = deepseekChatClient;
        this.metricsService = metricsService;
    }

    @GetMapping
    public Result<String> chat(@RequestParam String message) {
        long start = System.currentTimeMillis();
        String reply = deepseekChatClient.prompt().user(message).call().content();
        metricsService.recordAIRequest("deepseek-r1:8b", "/api/chat", System.currentTimeMillis() - start);
        return Result.success(reply);
    }
}
