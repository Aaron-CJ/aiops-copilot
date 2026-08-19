package com.aiops.aiopscopilot.controller;

import com.aiops.aiopscopilot.common.result.Result;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient deepseekChatClient;

    public ChatController(ChatClient deepseekChatClient) {
        this.deepseekChatClient = deepseekChatClient;
    }

    @GetMapping
    public Result<String> chat(@RequestParam String message) {
        String reply = deepseekChatClient.prompt().user(message).call().content();
        return Result.success(reply);
    }
}
