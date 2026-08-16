package com.aiops.aiopscopilot.common.exception;

import com.aiops.aiopscopilot.common.result.Result;
import com.aiops.aiopscopilot.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器，统一返回 Result 结构
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/**
	 * 处理自定义业务异常
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
	 * 处理未知异常
	 */
	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public Result<Void> handleException(Exception e) {
		log.error("系统未知异常", e);
		return Result.fail(ResultCode.INTERNAL_ERROR);
	}

	private String extractValidationMessage(Exception e) {
		if (e instanceof MethodArgumentNotValidException ex) {
			return ex.getBindingResult().getFieldErrors().stream()
					.findFirst()
					.map(error -> error.getField() + ": " + error.getDefaultMessage())
					.orElse(ResultCode.BAD_REQUEST.getMessage());
		}
		if (e instanceof BindException ex) {
			return ex.getBindingResult().getFieldErrors().stream()
					.findFirst()
					.map(error -> error.getField() + ": " + error.getDefaultMessage())
					.orElse(ResultCode.BAD_REQUEST.getMessage());
		}
		return ResultCode.BAD_REQUEST.getMessage();
	}

}
