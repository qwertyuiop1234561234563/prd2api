package com.dbappsecurity.prd2api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbappsecurity.prd2api.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统用户 Mapper
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按租户 + 账号查询用户
     */
    SysUser selectByTenantAndUsername(@Param("tenantId") String tenantId,
                                      @Param("username") String username);

    /**
     * 查询已过期但仍标记 VIP 的用户，供定时任务降级
     */
    List<SysUser> selectExpiredVip(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 原子累加登录失败次数，避免并发爆破绕过锁定阈值
     */
    int increaseFailCount(@Param("userId") String userId);

    /**
     * 锁定账号并清零失败计数
     */
    int lockUser(@Param("userId") String userId, @Param("lockUntil") LocalDateTime lockUntil);

    /**
     * 原子开通 VIP：同套餐且未过期时在原到期时间上累加，否则从当前时间开始计算
     * <p>
     * 单条 SQL 完成计算，避免并发开通时先读后写导致续期天数丢失。
     */
    int grantVip(@Param("userId") String userId,
                 @Param("planCode") String planCode,
                 @Param("days") int days);
}
