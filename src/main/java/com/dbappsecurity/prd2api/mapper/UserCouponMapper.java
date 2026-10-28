package com.dbappsecurity.prd2api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.entity.UserCoupon;
import com.dbappsecurity.prd2api.vo.UserCouponVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 用户优惠券 Mapper
 */
@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /**
     * 原子核销：仅 UNUSED 且未过期时可置为 USED
     */
    int markUsed(@Param("id") Long id,
                 @Param("userId") String userId,
                 @Param("now") LocalDateTime now);

    /**
     * 标记已过期的未使用优惠券
     */
    int markExpired(@Param("now") LocalDateTime now);

    /**
     * 管理端查看某批优惠券的领取记录（含账号信息）
     */
    IPage<UserCouponVO> selectClaimPage(Page<UserCouponVO> page, @Param("couponId") Long couponId);
}
