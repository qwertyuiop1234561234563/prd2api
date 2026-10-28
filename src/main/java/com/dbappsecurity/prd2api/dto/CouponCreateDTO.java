package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员发放优惠券入参
 */
@Data
@Schema(description = "管理员发放优惠券入参")
public class CouponCreateDTO {

    @NotBlank(message = "couponName 不能为空")
    @Size(max = 64, message = "优惠券名称最长 64 字符")
    @Schema(description = "优惠券名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "新人见面礼")
    private String couponName;

    @NotBlank(message = "planCode 不能为空")
    @Schema(description = "可开通套餐编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "BASIC")
    private String planCode;

    @Min(value = 0, message = "开通天数不能为负")
    @Max(value = 3650, message = "开通天数最大 3650")
    @Schema(description = "开通天数，0 表示沿用套餐自身天数", defaultValue = "0")
    private Integer durationDays = 0;

    @NotNull(message = "totalStock 不能为空")
    @Min(value = 1, message = "发放总量至少为 1")
    @Max(value = 100000, message = "发放总量最大 100000")
    @Schema(description = "发放总量", requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    private Integer totalStock;

    @Min(value = 1, message = "每人限领至少为 1")
    @Max(value = 100, message = "每人限领最大 100")
    @Schema(description = "每人限领数量", defaultValue = "1")
    private Integer perUserLimit = 1;

    @Min(value = 0, message = "领取截止天数不能为负")
    @Max(value = 3650, message = "领取截止天数最大 3650")
    @Schema(description = "多少天后停止领取，0 表示长期有效", defaultValue = "0")
    private Integer expireDays = 0;

    @Min(value = 0, message = "有效天数不能为负")
    @Max(value = 3650, message = "有效天数最大 3650")
    @Schema(description = "领取后多少天内可使用，0 表示跟随领取截止时间", defaultValue = "0")
    private Integer validDays = 0;
}
