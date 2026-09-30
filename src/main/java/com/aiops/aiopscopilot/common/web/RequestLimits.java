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
        if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "消息过长（上限 " + MAX_MESSAGE_LENGTH + " 字符）");
        }
    }
}
