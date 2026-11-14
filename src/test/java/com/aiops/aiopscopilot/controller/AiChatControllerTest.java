package com.aiops.aiopscopilot.controller;

import java.time.Duration;
import java.util.List;

import com.aiops.aiopscopilot.common.web.RequestLimits;
import com.aiops.aiopscopilot.service.KnowledgeIngester;
import com.aiops.aiopscopilot.service.MetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.codec.ServerSentEvent;

import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * AiChatController 流式端点的契约（纯单测，不起 Spring 容器）：
 * <ul>
 *   <li>输入契约（2026-11-14）：EventSource 固定携带 {@code Accept: text/event-stream}，
 *       缺参/超长若同步抛 BusinessException，经全局异常器产出的 JSON Result 会因内容协商
 *       失败退化为容器 406 裸错误——因此以首个 {@code [ERROR]} data 事件返回，不抛异常</li>
 *   <li>心跳契约（2026-11-14）：静默期（r1 思考可达数分钟）以 SSE 注释行 {@code :ping}
 *       保活——EventSource 忽略注释，不污染"按序拼接"契约；数据完成即停，不拖延收尾</li>
 * </ul>
 */
class AiChatControllerTest {

    private final AiChatController controller = new AiChatController(
            mock(ChatClient.class), mock(VectorStore.class), mock(KnowledgeIngester.class),
            new MetricsService(new SimpleMeterRegistry()), "deepseek-r1:8b");

    private final HttpServletResponse response = mock(HttpServletResponse.class);

    @Test
    void chatMissingMessageYieldsErrorEventInsteadOfThrownException() {
        List<ServerSentEvent<String>> events = controller.chat(null, response).collectList().block();
        assertEquals(1, events.size(), String.valueOf(events));
        assertEquals("[ERROR] 缺少 message 参数", events.getFirst().data(), String.valueOf(events));
    }

    @Test
    void chatOverlongMessageYieldsErrorEventInsteadOfThrownException() {
        List<ServerSentEvent<String>> events = controller.chat(
                "x".repeat(RequestLimits.MAX_MESSAGE_LENGTH + 1), response).collectList().block();
        assertEquals(1, events.size(), String.valueOf(events));
        assertTrue(events.getFirst().data().startsWith("[ERROR]"), String.valueOf(events));
        assertTrue(events.getFirst().data().contains(String.valueOf(RequestLimits.MAX_MESSAGE_LENGTH)),
                String.valueOf(events));
    }

    @Test
    void ragStreamMissingMessageYieldsErrorEventInsteadOfThrownException() {
        List<ServerSentEvent<String>> events = controller.ragStream(null, response).collectList().block();
        assertEquals(1, events.size(), String.valueOf(events));
        assertEquals("[ERROR] 缺少 message 参数", events.getFirst().data(), String.valueOf(events));
    }

    @Test
    void ragStreamOverlongMessageYieldsErrorEventInsteadOfThrownException() {
        List<ServerSentEvent<String>> events = controller.ragStream(
                "x".repeat(RequestLimits.MAX_MESSAGE_LENGTH + 1), response).collectList().block();
        assertEquals(1, events.size(), String.valueOf(events));
        assertTrue(events.getFirst().data().startsWith("[ERROR]"), String.valueOf(events));
        assertTrue(events.getFirst().data().contains(String.valueOf(RequestLimits.MAX_MESSAGE_LENGTH)),
                String.valueOf(events));
    }

    /** 静默期（数据流永不发射）心跳每 50ms 一条注释行：无 data 字段，客户端按规范忽略 */
    @Test
    void heartbeatEmittedDuringSilenceAsCommentOnlyEvents() {
        List<ServerSentEvent<String>> events = AiChatController
                .withHeartbeat(Flux.never(), Duration.ofMillis(50))
                .take(2)
                .collectList()
                .block(Duration.ofSeconds(5));
        assertEquals(2, events.size(), String.valueOf(events));
        assertNull(events.get(0).data(), "心跳是注释行，不得携带 data 字段");
        assertEquals("ping", events.get(0).comment(), String.valueOf(events));
    }

    /** 数据完成即停心跳：collectList 立即返回（若心跳未停，block(5s) 必超时失败） */
    @Test
    void heartbeatStopsWhenDataCompletes() {
        List<ServerSentEvent<String>> events = AiChatController
                .withHeartbeat(Flux.just("回答正文"), Duration.ofMillis(50))
                .collectList()
                .block(Duration.ofSeconds(5));
        assertEquals(1, events.size(), String.valueOf(events));
        assertEquals("回答正文", events.getFirst().data(), String.valueOf(events));
    }
}
