package com.dbappsecurity.prd2api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbappsecurity.prd2api.entity.RedeemCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * VIP 兑换码 Mapper
 */
@Mapper
public interface RedeemCodeMapper extends BaseMapper<RedeemCode> {

    /**
     * 原子抢占兑换码，返回 0 表示已被使用或状态变更
     */
    int claim(@Param("code") String code,
              @Param("userId") String userId,
              @Param("usedTime") LocalDateTime usedTime);

    /**
     * 标记已过期的未使用兑换码
     */
    int markExpired(@Param("now") LocalDateTime now);
}
