package com.aiops.aiopscopilot.common.exception;

import com.aiops.aiopscopilot.common.result.ResultCode;

import java.io.Serial;

/**
 * 业务异常：由 GlobalExceptionHandler 捕获后包装成 {@code Result.fail(code, msg)}，
 * HTTP 状态码仍为 200（业务失败不是协议故障，约定见 Result 类注释）。
 */
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int code;

    public BusinessException(String message) {
        this(ResultCode.BUSINESS_ERROR.getCode(), message);
    }

    public BusinessException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }

    public BusinessException(ResultCode resultCode, String message) {
        this(resultCode.getCode(), message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

}
