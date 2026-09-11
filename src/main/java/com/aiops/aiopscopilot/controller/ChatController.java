package com.aiops.aiopscopilot.controller;

import com.aiops.aiopscopilot.common.result.Result;
import com.aiops.aiopscopilot.service.MetricsService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
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
    private final String reasoningModel;

    public ChatController(ChatClient deepseekChatClient,
                          MetricsService metricsService,
                          @Value("${spring.ai.ollama.chat.model}") String reasoningModel) {
        this.deepseekChatClient = deepseekChatClient;
        this.metricsService = metricsService;
        this.reasoningModel = reasoningModel;
    }

    @GetMapping
    public Result<String> chat(@RequestParam String message) {
        long start = System.currentTimeMillis();
        String reply = deepseekChatClient.prompt().user(message).call().content();
        metricsService.recordAIRequest(reasoningModel, "/api/chat", System.currentTimeMillis() - start);
        return Result.success(reply);
    }
}
