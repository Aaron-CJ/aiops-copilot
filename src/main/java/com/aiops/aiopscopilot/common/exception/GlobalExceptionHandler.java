package com.aiops.aiopscopilot.common.exception;

import com.aiops.aiopscopilot.common.result.Result;
import com.aiops.aiopscopilot.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：任何 Controller 抛出的异常都在这里收敛为统一 {@link Result} 结构，
 * 前端不会收到裸 HTML 错误页。HTTP 状态码策略是刻意分裂的：
 * 业务异常回 200（错误语义放 body.code），参数类问题回 400，资源不存在回 404，
 * 未知异常才回 500——各 handler 上的注释说明了各自的理由。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理自定义业务异常。
     * <p>
     * HTTP 状态码刻意返回 200：业务失败不是协议/服务故障，错误语义由响应体中的
     * 业务 code（ResultCode.BUSINESS_ERROR=1000）表达，前端按 body.code 分支即可，
     * 避免 HTTP 4xx/5xx 被浏览器/网关拦截后拿不到结构化错误信息。
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理参数校验异常（@Valid / @Validated）
     */
    @ExceptionHandler({ MethodArgumentNotValidException.class, BindException.class })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(Exception e) {
        String message = extractValidationMessage(e);
        log.warn("参数校验失败: {}", message);
        return Result.fail(ResultCode.BAD_REQUEST, message);
    }

    /**
     * 处理缺少请求参数异常（@RequestParam 必填参数缺失）
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParameterException(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return Result.fail(ResultCode.BAD_REQUEST, "缺少必需的请求参数: " + e.getParameterName());
    }

    /**
     * 处理参数类型不匹配异常（如 /api/debug/memory-leak?mb=abc 传给 int 参数）。
     * 不单独处理会落进兜底 Exception handler 误报 500。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.warn("请求参数类型错误: {}={}", e.getName(), e.getValue());
        return Result.fail(ResultCode.BAD_REQUEST, "参数类型错误: " + e.getName());
    }

    /**
     * 处理静态资源不存在异常（如浏览器自动请求 favicon.ico）
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFoundException(NoResourceFoundException e) {
        log.debug("静态资源不存在: {}", e.getResourcePath());
        return Result.fail(ResultCode.NOT_FOUND);
    }

    /**
     * HTTP 方法不支持（如对只读端点发 POST）——2026-11-13：此前与 406/415 一同落入
     * 兜底 Exception handler 误报 500，污染错误监控与客户端重试判断。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.debug("HTTP 方法不支持: {}", e.getMessage());
        return Result.fail(ResultCode.METHOD_NOT_ALLOWED);
    }

    /** 客户端 Accept 头与响应类型不匹配（如对 SSE 端点不接受 text/event-stream） */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    @ResponseStatus(HttpStatus.NOT_ACCEPTABLE)
    public Result<Void> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        log.debug("响应类型不可接受: {}", e.getMessage());
        return Result.fail(ResultCode.NOT_ACCEPTABLE);
    }

    /** 请求体 Content-Type 不匹配（如 POST 缺 application/json） */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Result<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.debug("请求体类型不支持: {}", e.getMessage());
        return Result.fail(ResultCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /** 请求体不可读（JSON 格式坏/编码错）——参数类问题，回 400 而非 500 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.debug("请求体不可读: {}", e.getMessage());
        return Result.fail(ResultCode.BAD_REQUEST, "请求体格式错误");
    }

    /**
     * 请求参数字节无法按 UTF-8 解码（如客户端用 GBK 发中文表单/查询串）——Tomcat 在
     * getParameter 时抛出，经 Spring 参数解析器冒泡。参数类问题应回 400 而非落兜底 500
     * （2026-11-14 实测：真实客户端发坏编码字节 → 500"系统内部错误"，语义失真）。
     * 注意这是 org.apache.tomcat.util.http 的异常，与 java.security 的同名类无关；
     * 若此类请求落在 SSE 端点上，本 JSON 响应会因内容协商退化为 406（与其它同步
     * 异常 handler 同一边界，SSE 端点的输入校验已前移进控制器）。
     */
    @ExceptionHandler(org.apache.tomcat.util.http.InvalidParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleInvalidParameterEncoding(org.apache.tomcat.util.http.InvalidParameterException e) {
        log.warn("请求参数编码错误（非 UTF-8 字节）: {}", e.getMessage());
        return Result.fail(ResultCode.BAD_REQUEST, "请求参数编码错误（需 UTF-8）");
    }

    /**
     * 客户端断开（SSE 流式中途消失，容器异步错误通知）：响应通道已不可用，任何回写——
     * 包括本 handler 的 JSON——都会二次失败并刷 "Failure in @ExceptionHandler"。
     * 降为 debug 记录并静默完成，不再落 500 兜底（2026-11-14 断连实测：
     * 此前产生"系统未知异常" ERROR + handler 二次失败双重噪音）。
     * 注：配合 SSE 心跳（AiChatController），断开在 ≤15s 的下一次心跳写时即被发现。
     */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(
            org.springframework.web.context.request.async.AsyncRequestNotUsableException e) {
        log.debug("客户端已断开（SSE 流式中断）: {}", e.getMessage());
    }

    /**
     * 处理未知异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统未知异常", e);
        return Result.fail(ResultCode.INTERNAL_ERROR);
    }

    /**
     * 提取参数校验错误信息。{@link MethodArgumentNotValidException} 是 {@link BindException}
     * 的子类（spring-web 7 实测），一个 instanceof 即覆盖两类，无需重复分支；
     * 兜底返回仅为防御非 BindException 入参。
     */
    private String extractValidationMessage(Exception e) {
        if (e instanceof BindException ex) {
            return ex.getBindingResult().getFieldErrors().stream()
                    .findFirst()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .orElse(ResultCode.BAD_REQUEST.getMessage());
        }
        return ResultCode.BAD_REQUEST.getMessage();
    }

}
