package com.aiops.aiopscopilot.common.web;

import com.aiops.aiopscopilot.common.exception.BusinessException;
import com.aiops.aiopscopilot.common.result.ResultCode;

/**
 * API 输入边界校验：用户消息（直接进 prompt 的自由文本）长度上限。
 * <p>
 * 超长输入会原样拼进 prompt 打到 Ollama，浪费推理资源甚至触发超长上下文报错；
 * 在 controller 入口统一拦截。上限刻意宽松（1 万字符），只挡滥用不挡正常提问。
 */
public final class RequestLimits {

    public static final int MAX_MESSAGE_LENGTH = 10_000;

    private RequestLimits() {
    }

    /** 校验用户消息长度，超限抛 {@link BusinessException}（HTTP 200 + code 400，由全局异常器收敛） */
    public static void checkMessage(String message) {
        String violation = violationMessage(message);
        if (violation != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, violation);
        }
    }

    /**
     * SSE 端点专用的非抛校验：返回超限的错误文案（未超限返回 null）。
     * <p>
     * EventSource 固定携带 {@code Accept: text/event-stream}，流式端点上同步抛出的
     * {@link BusinessException} 经全局异常器产出 JSON Result——内容协商失败退化为
     * 容器 406 裸错误，客户端拿不到约定的错误结构。因此流式端点不抛异常，把错误
     * 作为首个 {@code [ERROR]} 事件推送（与流中段错误的推送形态一致，2026-11-14 审查修复）。
     * 缺参（message == null）同样不在本方法内表达，由流式端点自行返回 [ERROR] 事件。
     */
    public static String violationMessage(String message) {
        if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
            return "消息过长（上限 " + MAX_MESSAGE_LENGTH + " 字符）";
        }
        return null;
    }
}
