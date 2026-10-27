package com.dbappsecurity.prd2api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbappsecurity.prd2api.entity.TenantQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 租户配额 Mapper（原子计数，避免并发超卖）
 */
@Mapper
public interface TenantQuotaMapper extends BaseMapper<TenantQuota> {

    /**
     * 原子占用一次上传配额，返回 0 表示已超限
     */
    int increaseUploadUsed(@Param("tenantId") String tenantId);

    /**
     * 原子累计 token 消耗，返回 0 表示已超限
     */
    int increaseTokenUsed(@Param("tenantId") String tenantId, @Param("tokens") long tokens);

    /**
     * 重置配额计数
     */
    int resetUsed(@Param("tenantId") String tenantId, @Param("nextResetTime") java.time.LocalDateTime nextResetTime);
}
