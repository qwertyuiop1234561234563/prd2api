package com.dbappsecurity.prd2api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbappsecurity.prd2api.entity.Coupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 优惠券发放批次 Mapper
 */
@Mapper
public interface CouponMapper extends BaseMapper<Coupon> {

    /**
     * 原子累加已领取数量，库存不足或已下架时返回 0
     */
    int increaseClaimed(@Param("couponId") Long couponId);

    /**
     * 领取落库失败时回退已领取数量（补偿）
     */
    int decreaseClaimed(@Param("couponId") Long couponId);

    /**
     * 下架已过领取截止时间的优惠券
     */
    int markExpired(@Param("now") LocalDateTime now);
}
