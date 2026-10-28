package com.dbappsecurity.prd2api.vo;

import com.dbappsecurity.prd2api.entity.Coupon;
import com.dbappsecurity.prd2api.entity.VipPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 优惠券视图
 */
@Data
@Schema(description = "优惠券视图")
public class CouponVO {

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

    @Schema(description = "发放总量")
    private Integer totalStock;

    @Schema(description = "已领取数量")
    private Integer claimedCount;

    @Schema(description = "剩余库存")
    private Integer remainStock;

    @Schema(description = "每人限领数量")
    private Integer perUserLimit;

    @Schema(description = "当前用户已领数量")
    private Integer claimedByMe;

    @Schema(description = "领取后有效天数，0 表示跟随领取截止时间")
    private Integer validDays;

    @Schema(description = "领取开始时间")
    private LocalDateTime startTime;

    @Schema(description = "领取截止时间")
    private LocalDateTime expireTime;

    @Schema(description = "状态 1 领取中 0 已下架")
    private Integer status;

    @Schema(description = "发放人")
    private String createBy;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public static CouponVO from(Coupon coupon, VipPlan plan, Integer claimedByMe) {
        CouponVO vo = new CouponVO();
        vo.setCouponId(coupon.getCouponId());
        vo.setCouponName(coupon.getCouponName());
        vo.setPlanCode(coupon.getPlanCode());
        vo.setPlanName(plan == null ? null : plan.getPlanName());
        int days = coupon.getDurationDays() != null && coupon.getDurationDays() > 0
                ? coupon.getDurationDays()
                : (plan == null || plan.getDurationDays() == null ? 0 : plan.getDurationDays());
        vo.setDurationDays(days);
        vo.setTotalStock(coupon.getTotalStock());
        vo.setClaimedCount(coupon.getClaimedCount());
        int claimed = coupon.getClaimedCount() == null ? 0 : coupon.getClaimedCount();
        int total = coupon.getTotalStock() == null ? 0 : coupon.getTotalStock();
        vo.setRemainStock(Math.max(0, total - claimed));
        vo.setPerUserLimit(coupon.getPerUserLimit());
        vo.setClaimedByMe(claimedByMe == null ? 0 : claimedByMe);
        vo.setValidDays(coupon.getValidDays());
        vo.setStartTime(coupon.getStartTime());
        vo.setExpireTime(coupon.getExpireTime());
        vo.setStatus(coupon.getStatus());
        vo.setCreateBy(coupon.getCreateBy());
        vo.setCreateTime(coupon.getCreateTime());
        return vo;
    }
}
