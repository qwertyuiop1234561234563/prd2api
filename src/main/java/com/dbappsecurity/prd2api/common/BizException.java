package com.dbappsecurity.prd2api.common;

import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import lombok.Getter;

/**
 * 业务异常：所有可预期错误统一抛出该异常，由全局异常处理器转换为 RespModel
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BizException(BizErrorCodeEnum errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(BizErrorCodeEnum errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BizException(BizErrorCodeEnum errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
