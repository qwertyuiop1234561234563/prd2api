package com.dbappsecurity.prd2api.common;

import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.util.TraceIdUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一返回体
 *
 * @param <T> 业务数据类型
 */
@Data
@Schema(description = "统一返回体")
public class RespModel<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "业务状态码，0 表示成功", example = "0")
    private int code;

    @Schema(description = "提示信息", example = "成功")
    private String message;

    @Schema(description = "业务数据")
    private T data;

    @Schema(description = "全链路追踪ID")
    private String traceId;

    @Schema(description = "服务端时间戳")
    private long timestamp;

    public static <T> RespModel<T> success(T data) {
        RespModel<T> resp = new RespModel<>();
        resp.setCode(BizErrorCodeEnum.SUCCESS.getCode());
        resp.setMessage(BizErrorCodeEnum.SUCCESS.getMessage());
        resp.setData(data);
        resp.setTraceId(TraceIdUtil.getTraceId());
        resp.setTimestamp(System.currentTimeMillis());
        return resp;
    }

    public static <T> RespModel<T> success() {
        return success(null);
    }

    public static <T> RespModel<T> fail(BizErrorCodeEnum errorCode) {
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    public static <T> RespModel<T> fail(BizErrorCodeEnum errorCode, String message) {
        return fail(errorCode.getCode(), message);
    }

    public static <T> RespModel<T> fail(int code, String message) {
        RespModel<T> resp = new RespModel<>();
        resp.setCode(code);
        resp.setMessage(message);
        resp.setTraceId(TraceIdUtil.getTraceId());
        resp.setTimestamp(System.currentTimeMillis());
        return resp;
    }

    /**
     * 业务码到 HTTP 状态码的映射：错误码本身按 HTTP 语义定义，超出范围一律按 500 处理
     *
     * @param code 业务状态码
     * @return 合法的 HTTP 状态码
     */
    public static int toHttpStatus(int code) {
        return code >= 400 && code <= 599 ? code : 500;
    }
}
