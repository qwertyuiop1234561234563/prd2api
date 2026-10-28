package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 优惠券分页查询入参（管理员）
 */
@Data
@Schema(description = "优惠券分页查询入参")
public class CouponQueryDTO {

    @Min(value = 1, message = "pageNum 最小为 1")
    @Schema(description = "页码，从 1 开始", defaultValue = "1")
    private int pageNum = 1;

    @Min(value = 1, message = "pageSize 最小为 1")
    @Max(value = 100, message = "pageSize 最大为 100")
    @Schema(description = "每页条数", defaultValue = "10")
    private int pageSize = 10;

    @Schema(description = "状态 1 领取中 0 已下架")
    private Integer status;

    @Schema(description = "套餐编码")
    private String planCode;

    @Schema(description = "优惠券名称，模糊匹配")
    private String couponName;
}
