package com.aiops.aiopscopilot.common.result;

/**
 * 通用响应状态码枚举。业务端点优先使用本枚举表达错误码；
 * 确需枚举之外的语义码时也可内联 HTTP 风格码，约定详见 {@link Result} 类注释。
 */
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    /** HTTP 方法不支持（如对只读端点发 POST，2026-11-13：此前落入 500 兜底） */
    METHOD_NOT_ALLOWED(405, "HTTP 方法不支持"),
    /** 客户端 Accept 头与响应类型不匹配（如对 SSE 端点不接受 text/event-stream） */
    NOT_ACCEPTABLE(406, "响应类型不可接受"),
    /** 请求体 Content-Type 不匹配（如 POST 缺 application/json） */
    UNSUPPORTED_MEDIA_TYPE(415, "请求体类型不支持"),
    /** 资源满/过载等暂不可用场景（如深度诊断任务队列已满） */
    SERVICE_UNAVAILABLE(503, "服务暂不可用"),
    INTERNAL_ERROR(500, "系统内部错误"),
    BUSINESS_ERROR(1000, "业务处理失败");

    private final int code;

    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

}
