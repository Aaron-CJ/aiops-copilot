package com.aiops.aiopscopilot.common.web;

import com.aiops.aiopscopilot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequestLimits} 的消息长度边界（纯单测）。
 * <p>
 * 运行时 GET query 会先被 Tomcat 的 8KB header 上限拒绝，应用层这道边界只能通过
 * POST form body 触达，因此用单测把「刚好等于上限放行 / 超一个字符拒绝」钉死，
 * 不依赖容器配置。
 */
class RequestLimitsTest {

    @Test
    void nullAndEmptyMessagesAreAccepted() {
        assertDoesNotThrow(() -> RequestLimits.checkMessage(null));
        assertDoesNotThrow(() -> RequestLimits.checkMessage(""));
    }

    @Test
    void messageExactlyAtLimitIsAccepted() {
        assertDoesNotThrow(() ->
                RequestLimits.checkMessage("x".repeat(RequestLimits.MAX_MESSAGE_LENGTH)));
    }

    @Test
    void messageOverLimitIsRejectedWithBadRequest() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RequestLimits.checkMessage("x".repeat(RequestLimits.MAX_MESSAGE_LENGTH + 1)));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("消息过长"));
        assertTrue(ex.getMessage().contains(String.valueOf(RequestLimits.MAX_MESSAGE_LENGTH)));
    }

    /**
     * （2026-11-14）SSE 端点专用的非抛校验：可接受的输入（null/空串/恰好等于上限）
     * 返回 null，供流式端点据此放行（violationMessage != null 才返回 [ERROR] 事件）。
     */
    @Test
    void violationMessageIsNullForAcceptableInput() {
        assertNull(RequestLimits.violationMessage(null), "null 由流式端点的缺参分支处理，非长度违规");
        assertNull(RequestLimits.violationMessage(""));
        assertNull(RequestLimits.violationMessage("x".repeat(RequestLimits.MAX_MESSAGE_LENGTH)));
    }

    /** 超限输入返回错误文案而非抛异常（文案与 checkMessage 抛出的 BusinessException 一致） */
    @Test
    void violationMessageDescribesLimitForOverlongInput() {
        String violation = RequestLimits.violationMessage(
                "x".repeat(RequestLimits.MAX_MESSAGE_LENGTH + 1));
        assertEquals("消息过长（上限 " + RequestLimits.MAX_MESSAGE_LENGTH + " 字符）", violation);
    }
}
