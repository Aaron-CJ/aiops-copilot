package com.aiops.aiopscopilot.controller;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private final ChatClient deepseekChatClient;

    public AiChatController(@Qualifier("deepseekChatClient") ChatClient deepseekChatClient) {
        this.deepseekChatClient = deepseekChatClient;
    }

    /**
     * 流式对话接口：以 SSE 逐段推送模型输出，事件按序拼接即为完整回复。
     *
     * 开启 thinking 后 Ollama 会把 deepseek-r1 的思考过程放在单独的 thinking 字段返回，
     * 这里将其重新包装为 <think>...</think> 并与最终回答按序推送，前端可据此区分渲染；
     * 思考内容由模型自行决定，简单问题可能没有思考段。
     *
     * 注意：SSE 响应头在首个事件写入时即提交为 200，此后模型调用出错无法再返回
     * 错误状态码，会改以 [ERROR] 开头的文本事件推送。
     *
     * @param message 用户提问
     * @return SSE 事件流（思考过程 + 最终回答）
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam String message, HttpServletResponse response) {
        // SseEmitter 会把 Content-Type 硬编码为不带 charset 的 text/event-stream，
        // 提前声明响应编码可让 Tomcat 提交响应头时追加 charset=UTF-8，避免浏览器乱码
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        AtomicBoolean thinkingOpened = new AtomicBoolean(false);
        return deepseekChatClient.prompt()
                .options(OllamaChatOptions.builder().enableThinking())
                .user(message)
                .stream()
                .chatResponse()
                .concatMapIterable(chatResponse -> {
                    List<String> chunks = new ArrayList<>();
                    var result = chatResponse.getResult();
                    if (result != null) {
                        String thinking = (String) result.getOutput().getMetadata().get("thinking");
                        if (thinking != null && !thinking.isEmpty()) {
                            if (thinkingOpened.compareAndSet(false, true)) {
                                chunks.add("<think>");
                            }
                            chunks.add(thinking);
                        }
                        String content = result.getOutput().getText();
                        if (content != null && !content.isEmpty()) {
                            if (thinkingOpened.compareAndSet(true, false)) {
                                chunks.add("</think>");
                            }
                            chunks.add(content);
                        }
                    }
                    return chunks;
                })
                .onErrorResume(e -> Flux.just("[ERROR] " + e.getMessage()));
    }
}
