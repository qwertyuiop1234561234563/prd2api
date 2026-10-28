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
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：统一返回 RespModel，并按业务码映射真实 HTTP 状态码
 * <p>
 * 仅返回 HTTP 200 会让网关、监控与客户端重试策略无法识别失败，因此此处保留业务码的同时同步 HTTP 状态码。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionConfig {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<RespModel<Void>> handleBizException(BizException e) {
        log.warn("业务异常 code={} msg={} traceId={}", e.getCode(), e.getMessage(), TraceIdUtil.getTraceId());
        return build(e.getCode(), RespModel.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<RespModel<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 {}", message);
        return build(BizErrorCodeEnum.PARAM_INVALID.getCode(), RespModel.fail(BizErrorCodeEnum.PARAM_INVALID, message));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<RespModel<Void>> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败 {}", message);
        return build(BizErrorCodeEnum.PARAM_INVALID.getCode(), RespModel.fail(BizErrorCodeEnum.PARAM_INVALID, message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<RespModel<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数约束校验失败 {}", message);
        return build(BizErrorCodeEnum.PARAM_INVALID.getCode(), RespModel.fail(BizErrorCodeEnum.PARAM_INVALID, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<RespModel<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败 {}", e.getMessage());
        return build(BizErrorCodeEnum.PARAM_INVALID.getCode(),
                RespModel.fail(BizErrorCodeEnum.PARAM_INVALID, "请求体格式错误，请检查 JSON 结构"));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<RespModel<Void>> handleBadRequest(Exception e) {
        log.warn("请求参数异常 {}", e.getMessage());
        return build(BizErrorCodeEnum.PARAM_INVALID.getCode(),
                RespModel.fail(BizErrorCodeEnum.PARAM_INVALID, e.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<RespModel<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持 {}", e.getMessage());
        return build(HttpStatus.METHOD_NOT_ALLOWED.value(),
                RespModel.fail(HttpStatus.METHOD_NOT_ALLOWED.value(), "不支持的请求方法: " + e.getMethod()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<RespModel<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("请求内容类型不支持 {}", e.getMessage());
        return build(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED.getCode(),
                RespModel.fail(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED, "不支持的 Content-Type: " + e.getContentType()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<RespModel<Void>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("请求路径不存在 uri={}", e.getResourcePath());
        return build(BizErrorCodeEnum.NOT_FOUND.getCode(), RespModel.fail(BizErrorCodeEnum.NOT_FOUND));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<RespModel<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("文件超过大小限制 {}", e.getMessage());
        return build(HttpStatus.PAYLOAD_TOO_LARGE.value(),
                RespModel.fail(HttpStatus.PAYLOAD_TOO_LARGE.value(), "文件超过大小上限，请查看当前套餐额度"));
    }

    @ExceptionHandler(BlockException.class)
    public ResponseEntity<RespModel<Void>> handleBlockException(BlockException e) {
        log.warn("Sentinel 限流/熔断触发 resource={}", e.getRule() == null ? "-" : e.getRule().getResource());
        return build(BizErrorCodeEnum.RATE_LIMITED.getCode(), RespModel.fail(BizErrorCodeEnum.RATE_LIMITED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RespModel<Void>> handleException(Exception e) {
        log.error("系统异常 traceId={}", TraceIdUtil.getTraceId(), e);
        return build(BizErrorCodeEnum.SYSTEM_ERROR.getCode(), RespModel.fail(BizErrorCodeEnum.SYSTEM_ERROR,
                "系统内部错误，请联系管理员，traceId=" + TraceIdUtil.getTraceId()));
    }

    private static ResponseEntity<RespModel<Void>> build(int code, RespModel<Void> body) {
        return ResponseEntity.status(RespModel.toHttpStatus(code)).body(body);
    }
}
