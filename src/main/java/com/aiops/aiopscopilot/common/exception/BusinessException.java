package com.aiops.aiopscopilot.common.exception;

import com.aiops.aiopscopilot.common.result.ResultCode;

import java.io.Serial;

/**
 * 自定义业务异常
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
