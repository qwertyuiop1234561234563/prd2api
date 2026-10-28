package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 优惠券发放批次
 * <p>
 * 限量发放：总量与每人限领共同约束，领取动作由 Redis Lua 原子扣减库存。
 */
@Data
@TableName("coupon")
public class Coupon {

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_OFFLINE = 0;

    @TableId(value = "coupon_id", type = IdType.AUTO)
    private Long couponId;

    private String couponName;

    private String planCode;

    /** 开通天数，0 表示沿用套餐自身天数 */
    private Integer durationDays;

    private Integer totalStock;

    private Integer perUserLimit;

    private Integer claimedCount;

    /** 领取后有效天数，0 表示跟随领取截止时间 */
    private Integer validDays;

    private LocalDateTime startTime;

    private LocalDateTime expireTime;

    private Integer status;

    private String createBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 当前时间是否可领取
     */
    public boolean claimable(LocalDateTime now) {
        if (status == null || status != STATUS_ACTIVE) {
            return false;
        }
        if (startTime != null && startTime.isAfter(now)) {
            return false;
        }
        return expireTime == null || expireTime.isAfter(now);
    }
}
