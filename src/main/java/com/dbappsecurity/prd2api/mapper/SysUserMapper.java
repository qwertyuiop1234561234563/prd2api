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
}
