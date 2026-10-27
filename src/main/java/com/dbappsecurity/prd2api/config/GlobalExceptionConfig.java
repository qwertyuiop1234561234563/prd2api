package com.dbappsecurity.prd2api.config;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.util.TraceIdUtil;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：统一返回 RespModel，避免异常堆栈直接暴露给调用方
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionConfig {

    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public RespModel<Void> handleBizException(BizException e) {
        log.warn("业务异常 code={} msg={} traceId={}", e.getCode(), e.getMessage(), TraceIdUtil.getTraceId());
        return RespModel.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public RespModel<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 {}", message);
        return RespModel.fail(BizErrorCodeEnum.PARAM_INVALID, message);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public RespModel<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败 {}", message);
        return RespModel.fail(BizErrorCodeEnum.PARAM_INVALID, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.OK)
    public RespModel<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数约束校验失败 {}", message);
        return RespModel.fail(BizErrorCodeEnum.PARAM_INVALID, message);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.OK)
    public RespModel<Void> handleBadRequest(Exception e) {
        log.warn("请求参数异常 {}", e.getMessage());
        return RespModel.fail(BizErrorCodeEnum.PARAM_INVALID, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.OK)
    public RespModel<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("文件超过大小限制 {}", e.getMessage());
        return RespModel.fail(BizErrorCodeEnum.PARAM_INVALID, "文件超过 20MB 限制");
    }

    @ExceptionHandler(BlockException.class)
    @ResponseStatus(HttpStatus.OK)
    public RespModel<Void> handleBlockException(BlockException e) {
        log.warn("Sentinel 限流/熔断触发 resource={}", e.getRule() == null ? "-" : e.getRule().getResource());
        return RespModel.fail(BizErrorCodeEnum.RATE_LIMITED);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public RespModel<Void> handleException(Exception e) {
        log.error("系统异常 traceId={}", TraceIdUtil.getTraceId(), e);
        return RespModel.fail(BizErrorCodeEnum.SYSTEM_ERROR,
                "系统内部错误，请联系管理员，traceId=" + TraceIdUtil.getTraceId());
    }
}
