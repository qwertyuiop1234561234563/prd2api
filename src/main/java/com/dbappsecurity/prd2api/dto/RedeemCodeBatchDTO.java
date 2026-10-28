package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 兑换码批量生成入参
 */
@Data
@Schema(description = "兑换码批量生成入参")
public class RedeemCodeBatchDTO {

    @NotBlank(message = "planCode 不能为空")
    @Schema(description = "套餐编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "BASIC")
    private String planCode;

    @Min(value = 1, message = "生成数量至少为 1")
    @Max(value = 500, message = "单次最多生成 500 个")
    @Schema(description = "生成数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "20")
    private Integer count;

    @Min(value = 0, message = "有效天数不能为负")
    @Max(value = 3650, message = "有效天数最大 3650")
    @Schema(description = "兑换码自身有效天数，0 表示长期有效", defaultValue = "0")
    private Integer expireDays = 0;
}
