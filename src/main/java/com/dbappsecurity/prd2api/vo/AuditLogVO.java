package com.dbappsecurity.prd2api.vo;

import com.dbappsecurity.prd2api.entity.AuditLog;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计视图
 */
@Data
@Schema(description = "操作审计视图")
public class AuditLogVO {

    @Schema(description = "日志ID")
    private Long id;

    @Schema(description = "租户ID")
    private String tenantId;

    @Schema(description = "用户标识")
    private String userKey;

    @Schema(description = "用户角色")
    private String userRole;

    @Schema(description = "操作动作")
    private String action;

    @Schema(description = "操作对象")
    private String target;

    @Schema(description = "HTTP 方法")
    private String httpMethod;

    @Schema(description = "请求路径")
    private String uri;

    @Schema(description = "客户端IP")
    private String clientIp;

    @Schema(description = "是否成功 1/0")
    private Integer success;

    @Schema(description = "耗时毫秒")
    private Long costMs;

    @Schema(description = "全链路追踪ID")
    private String traceId;

    @Schema(description = "失败原因")
    private String errorMsg;

    @Schema(description = "操作时间")
    private LocalDateTime createTime;

    public static AuditLogVO from(AuditLog log) {
        if (log == null) {
            return null;
        }
        AuditLogVO vo = new AuditLogVO();
        vo.setId(log.getId());
        vo.setTenantId(log.getTenantId());
        vo.setUserKey(log.getUserKey());
        vo.setUserRole(log.getUserRole());
        vo.setAction(log.getAction());
        vo.setTarget(log.getTarget());
        vo.setHttpMethod(log.getHttpMethod());
        vo.setUri(log.getUri());
        vo.setClientIp(log.getClientIp());
        vo.setSuccess(log.getSuccess());
        vo.setCostMs(log.getCostMs());
        vo.setTraceId(log.getTraceId());
        vo.setErrorMsg(log.getErrorMsg());
        vo.setCreateTime(log.getCreateTime());
        return vo;
    }
}
