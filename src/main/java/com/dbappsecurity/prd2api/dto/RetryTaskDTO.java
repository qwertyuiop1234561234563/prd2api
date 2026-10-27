package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 任务重试入参
 */
@Data
@Schema(description = "任务重试入参")
public class RetryTaskDTO {

    @NotBlank(message = "taskId 不能为空")
    @Schema(description = "任务ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskId;

    @Schema(description = "重试时可选切换模型编码，为空沿用原模型")
    private String modelCode;
}
