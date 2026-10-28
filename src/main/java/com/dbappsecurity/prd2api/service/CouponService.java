package com.dbappsecurity.prd2api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.dto.CouponClaimQueryDTO;
import com.dbappsecurity.prd2api.dto.CouponCreateDTO;
import com.dbappsecurity.prd2api.dto.CouponQueryDTO;
import com.dbappsecurity.prd2api.vo.CouponVO;
import com.dbappsecurity.prd2api.vo.MyVipVO;
import com.dbappsecurity.prd2api.vo.UserCouponVO;

import java.util.List;

/**
 * 优惠券服务：限量发放、Redis Lua 原子领取、核销开通 VIP、过期收敛
 */
public interface CouponService {

    /**
     * 可领取的优惠券列表（含当前用户已领数量）
     */
    List<CouponVO> listAvailable();

    /**
     * 领取优惠券：Redis Lua 原子扣减库存并校验每人限领
     */
    UserCouponVO claim(Long couponId);

    /**
     * 我的优惠券
     */
    List<UserCouponVO> myCoupons();

    /**
     * 使用优惠券开通 VIP
     */
    MyVipVO use(Long userCouponId);

    /**
     * 管理员发放优惠券
     */
    CouponVO create(CouponCreateDTO dto);

    /**
     * 优惠券分页（管理员）
     */
    Page<CouponVO> pageCoupons(CouponQueryDTO dto);

    /**
     * 优惠券下架，仅停止新领取，已领取仍可使用
     */
    void offline(Long couponId);

    /**
     * 某批优惠券的领取记录（管理员）
     */
    Page<UserCouponVO> pageClaims(Long couponId, CouponClaimQueryDTO dto);

    /**
     * 定时任务：收敛过期优惠券与过期用户券，返回处理条数
     */
    int expireOverdue();
}
