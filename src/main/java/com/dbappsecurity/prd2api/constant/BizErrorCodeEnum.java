package com.dbappsecurity.prd2api.constant;

import lombok.Getter;

/**
 * 业务错误码
 */
@Getter
public enum BizErrorCodeEnum {

    SUCCESS(0, "成功"),
    PARAM_INVALID(400, "参数校验失败"),
    UNAUTHORIZED(401, "身份未认证"),
    FORBIDDEN(403, "无权访问"),
    NOT_FOUND(404, "资源不存在"),
    TASK_STATE_ERROR(409, "任务状态不允许该操作"),
    DUPLICATE_DOCUMENT(409, "文档已存在"),
    FILE_TYPE_NOT_ALLOWED(415, "文件类型不支持"),
    QUOTA_EXCEEDED(429, "租户配额已超限"),
    RATE_LIMITED(429, "请求过于频繁，请稍后重试"),
    EXPORT_TOKEN_INVALID(403, "导出凭证无效或已过期"),
    MODEL_NOT_SUPPORTED(400, "不支持的模型编码"),
    ACCOUNT_LOCKED(423, "账号已被锁定"),
    REDEEM_CODE_INVALID(400, "兑换码无效"),
    VIP_PLAN_INVALID(400, "VIP 套餐无效"),
    BIZ_ERROR(500, "业务处理失败"),
    SYSTEM_ERROR(500, "系统内部错误");

    private final int code;
    private final String message;

    BizErrorCodeEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
