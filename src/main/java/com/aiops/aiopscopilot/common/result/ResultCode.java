package com.aiops.aiopscopilot.common.result;

/**
 * 统一响应状态码
 */
public enum ResultCode {

	SUCCESS(200, "操作成功"),
	BAD_REQUEST(400, "请求参数错误"),
	UNAUTHORIZED(401, "未授权"),
	FORBIDDEN(403, "禁止访问"),
	NOT_FOUND(404, "资源不存在"),
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
