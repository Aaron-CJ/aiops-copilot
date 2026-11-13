package com.aiops.aiopscopilot.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import com.aiops.aiopscopilot.common.result.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * GlobalExceptionHandler 的 HTTP 语义单测（2026-11-13）。
 * <p>
 * 背景：405/406/415 与坏 JSON 请求体此前都落入 {@code handleException(Exception)} 兜底
 * 误报 500——错误监控被污染，客户端也无法区分"服务坏了"与"请求姿势不对"。
 * handler 是普通方法，直接调用断言 Result.code 即可（@ResponseStatus 由 Spring MVC
 * 按注解生效，状态码与 body.code 保持一致）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 对只读端点发 POST：405，不再误报 500 */
    @Test
    void methodNotSupportedYields405() {
        Result<Void> r = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("POST"));
        assertEquals(405, r.getCode());
    }

    /** Accept 头与响应类型不匹配：406 */
    @Test
    void mediaTypeNotAcceptableYields406() {
        Result<Void> r = handler.handleMediaTypeNotAcceptable(
                new HttpMediaTypeNotAcceptableException("text/event-stream"));
        assertEquals(406, r.getCode());
    }

    /** Content-Type 不匹配：415 */
    @Test
    void mediaTypeNotSupportedYields415() {
        Result<Void> r = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException("text/plain"));
        assertEquals(415, r.getCode());
    }

    /** 坏 JSON 请求体：400（参数类问题），不再落 500 兜底 */
    @Test
    void unreadableBodyYields400() {
        Result<Void> r = handler.handleHttpMessageNotReadable(
                new HttpMessageNotReadableException("JSON 解析失败", (org.springframework.http.HttpInputMessage) null));
        assertEquals(400, r.getCode());
        assertFalse(r.isSuccess());
    }
}
