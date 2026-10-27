package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 操作审计分页查询入参
 */
@Data
@Schema(description = "操作审计分页查询入参")
public class AuditQueryDTO {

    @Min(value = 1, message = "pageNum 最小为 1")
    @Schema(description = "页码，从 1 开始", defaultValue = "1")
    private int pageNum = 1;

    @Min(value = 1, message = "pageSize 最小为 1")
    @Max(value = 100, message = "pageSize 最大为 100")
    @Schema(description = "每页条数", defaultValue = "10")
    private int pageSize = 10;

    @Schema(description = "租户ID，仅管理员可用；普通用户强制使用当前租户")
    private String tenantId;

    @Schema(description = "用户标识")
    private String userKey;

    @Schema(description = "操作动作，模糊匹配")
    private String action;

    @Schema(description = "是否成功：1 成功 0 失败")
    private Integer success;

    @Schema(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @Schema(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
