package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * VIP 套餐配置入参（管理员）
 */
@Data
@Schema(description = "VIP 套餐配置入参")
public class VipPlanDTO {

    @NotBlank(message = "planCode 不能为空")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "套餐编码仅允许大写字母、数字、下划线，长度 2~32")
    @Schema(description = "套餐编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String planCode;

    @NotBlank(message = "planName 不能为空")
    @Size(max = 64, message = "套餐名称最长 64 字符")
    @Schema(description = "套餐名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String planName;

    @NotNull(message = "level 不能为空")
    @Min(value = 0, message = "level 最小为 0")
    @Max(value = 99, message = "level 最大为 99")
    @Schema(description = "套餐等级 0免费 1基础 2专业", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer level;

    @NotNull(message = "durationDays 不能为空")
    @Min(value = 0, message = "有效天数不能为负")
    @Schema(description = "单次开通有效天数，0 表示不可开通", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer durationDays;

    @NotNull(message = "dailyUploadLimit 不能为空")
    @Min(value = 1, message = "每日上传上限至少为 1")
    @Schema(description = "每日上传次数上限", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer dailyUploadLimit;

    @NotNull(message = "dailyTokenLimit 不能为空")
    @Min(value = 1, message = "每日 token 上限至少为 1")
    @Schema(description = "每日 token 上限", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long dailyTokenLimit;

    @NotNull(message = "concurrentTaskLimit 不能为空")
    @Min(value = 1, message = "并发任务上限至少为 1")
    @Schema(description = "最大并发任务数", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer concurrentTaskLimit;

    @NotNull(message = "maxFileSizeMb 不能为空")
    @Min(value = 1, message = "单文件上限至少为 1MB")
    @Max(value = 200, message = "单文件上限最大 200MB")
    @Schema(description = "单文件大小上限 MB", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer maxFileSizeMb;

    @Size(max = 16, message = "模型档位最长 16 字符")
    @Schema(description = "可用模型档位 basic/standard/pro", defaultValue = "basic")
    private String modelTier;

    @Min(value = 0, message = "价格不能为负")
    @Schema(description = "参考价格，单位分", defaultValue = "0")
    private Integer priceCent;

    @Size(max = 256, message = "套餐说明最长 256 字符")
    @Schema(description = "套餐说明")
    private String description;

    @Schema(description = "1 上架 0 下架", defaultValue = "1")
    private Integer status;
}
