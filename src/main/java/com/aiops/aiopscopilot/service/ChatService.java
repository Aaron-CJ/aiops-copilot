package com.aiops.aiopscopilot.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final ChatClient deepseekChatClient;

    public ChatService(@Qualifier("deepseekChatClient")ChatClient deepseekChatClient) {
        this.deepseekChatClient = deepseekChatClient;
    }

    public String chat(String question) {
        return deepseekChatClient
                .prompt()
                .user(question)
                .call()
                .content();
    }
}
