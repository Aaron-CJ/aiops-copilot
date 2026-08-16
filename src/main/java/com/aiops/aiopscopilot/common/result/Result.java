package com.aiops.aiopscopilot.common.result;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一 API 响应对象
 *
 * @param <T> 业务数据类型
 */
public class Result<T> implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private int code;

	private String message;

	private T data;

	public Result() {
	}

	public Result(int code, String message, T data) {
		this.code = code;
		this.message = message;
		this.data = data;
	}

	public static <T> Result<T> success() {
		return success(null);
	}

	public static <T> Result<T> success(T data) {
		return success(ResultCode.SUCCESS.getMessage(), data);
	}

	public static <T> Result<T> success(String message, T data) {
		return new Result<>(ResultCode.SUCCESS.getCode(), message, data);
	}

	public static <T> Result<T> fail(ResultCode resultCode) {
		return fail(resultCode.getCode(), resultCode.getMessage());
	}

	public static <T> Result<T> fail(ResultCode resultCode, String message) {
		return fail(resultCode.getCode(), message);
	}

	public static <T> Result<T> fail(int code, String message) {
		return new Result<>(code, message, null);
	}

	public static <T> Result<T> fail(int code, String message, T data) {
		return new Result<>(code, message, data);
	}

	public boolean isSuccess() {
		return ResultCode.SUCCESS.getCode() == this.code;
	}

	public int getCode() {
		return code;
	}

	public void setCode(int code) {
		this.code = code;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public T getData() {
		return data;
	}

	public void setData(T data) {
		this.data = data;
	}

}
