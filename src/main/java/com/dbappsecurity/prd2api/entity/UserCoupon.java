package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户已领取的优惠券
 */
@Data
@TableName("user_coupon")
public class UserCoupon {

    public static final String STATUS_UNUSED = "UNUSED";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long couponId;

    private String userId;

    private String tenantId;

    /** 该用户第几次领取，与 coupon_id/user_id 组成唯一键 */
    private Integer claimNo;

    private String status;

    private LocalDateTime receiveTime;

    private LocalDateTime useTime;

    private LocalDateTime expireTime;

    private LocalDateTime createTime;
}
