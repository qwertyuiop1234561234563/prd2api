package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户优惠券视图
 */
@Data
@Schema(description = "用户优惠券视图")
public class UserCouponVO {

    @Schema(description = "用户优惠券主键")
    private Long id;

    @Schema(description = "优惠券ID")
    private Long couponId;

    @Schema(description = "优惠券名称")
    private String couponName;

    @Schema(description = "套餐编码")
    private String planCode;

    @Schema(description = "套餐名称")
    private String planName;

    @Schema(description = "实际开通天数")
    private Integer durationDays;

    @Schema(description = "用户ID")
    private String userId;

    @Schema(description = "登录账号")
    private String username;

    @Schema(description = "显示名")
    private String displayName;

    @Schema(description = "该用户第几次领取")
    private Integer claimNo;

    @Schema(description = "状态 UNUSED / USED / EXPIRED")
    private String status;

    @Schema(description = "领取时间")
    private LocalDateTime receiveTime;

    @Schema(description = "使用时间")
    private LocalDateTime useTime;

    @Schema(description = "使用截止时间")
    private LocalDateTime expireTime;
}
