package com.aiops.aiopscopilot.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 基础对话服务：不走知识库检索的最简 ChatClient 用法（同步阻塞调用）。
 * 带知识库检索的版本见 {@link KnowledgeIngester} + AiChatController#rag。
 */
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
