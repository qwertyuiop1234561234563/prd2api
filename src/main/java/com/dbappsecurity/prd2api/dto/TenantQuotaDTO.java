package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 租户配额配置入参（管理员）
 */
@Data
@Schema(description = "租户配额配置入参")
public class TenantQuotaDTO {

    @NotBlank(message = "tenantId 不能为空")
    @Schema(description = "租户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantId;

    @NotNull(message = "dailyUploadLimit 不能为空")
    @Min(value = 1, message = "每日上传上限至少为 1")
    @Schema(description = "每日上传次数上限", example = "50")
    private Integer dailyUploadLimit;

    @NotNull(message = "dailyTokenLimit 不能为空")
    @Min(value = 1, message = "每日 token 上限至少为 1")
    @Schema(description = "每日 token 上限", example = "1000000")
    private Long dailyTokenLimit;

    @NotNull(message = "concurrentTaskLimit 不能为空")
    @Min(value = 1, message = "并发任务上限至少为 1")
    @Schema(description = "最大并发任务数", example = "5")
    private Integer concurrentTaskLimit;
}
