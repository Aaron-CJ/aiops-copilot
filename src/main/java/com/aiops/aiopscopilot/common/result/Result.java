package com.aiops.aiopscopilot.common.result;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一 API 响应对象：{@code {code, message, data}}，JSON 中另带一个由 {@link #isSuccess()}
 * 序列化出的 {@code success} 布尔字段，前端可直接判断。
 * <p>
 * 项目约定（见 {@link com.aiops.aiopscopilot.common.exception.GlobalExceptionHandler}）：
 * 业务成败全部 HTTP 200，语义看 body 里的 {@code code}——成功 200；
 * 业务级 404/503（如任务不存在、队列已满）直接复用 HTTP 风格数字；
 * 通用业务失败 1000。只有参数错误（400）、真正的服务异常（500）等才用真实 HTTP 状态码。
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
