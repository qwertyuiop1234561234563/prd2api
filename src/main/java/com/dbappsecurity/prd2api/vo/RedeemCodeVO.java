package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 兑换码视图
 */
@Data
@Schema(description = "兑换码视图")
public class RedeemCodeVO {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "兑换码")
    private String code;

    @Schema(description = "套餐编码")
    private String planCode;

    @Schema(description = "套餐名称")
    private String planName;

    @Schema(description = "状态 UNUSED / USED / EXPIRED / VOID")
    private String status;

    @Schema(description = "批次号")
    private String batchNo;

    @Schema(description = "使用人用户ID")
    private String usedByUser;

    @Schema(description = "使用时间")
    private LocalDateTime usedTime;

    @Schema(description = "兑换码过期时间")
    private LocalDateTime expireTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
