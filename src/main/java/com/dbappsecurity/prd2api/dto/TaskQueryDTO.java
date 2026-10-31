package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 任务分页查询入参
 */
@Data
@Schema(description = "任务分页查询入参")
public class TaskQueryDTO {

    @Min(value = 1, message = "pageNum 最小为 1")
    @Max(value = 1000, message = "pageNum 最大为 1000")
    @Schema(description = "页码，从 1 开始", defaultValue = "1")
    private int pageNum = 1;

    @Min(value = 1, message = "pageSize 最小为 1")
    @Max(value = 100, message = "pageSize 最大为 100")
    @Schema(description = "每页条数", defaultValue = "10")
    private int pageSize = 10;

    @Schema(description = "任务状态过滤：INIT / PROCESS / SUCCESS / FAIL，为空查全部")
    private String taskStatus;

    @Schema(description = "文件元数据ID，用于回溯同一文档的多版本报告")
    private String fileMetaId;

    @Schema(description = "租户ID，仅管理员可用；普通用户强制使用当前租户")
    private String tenantId;
}
