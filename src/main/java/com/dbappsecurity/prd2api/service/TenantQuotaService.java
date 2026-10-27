package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.dto.TenantQuotaDTO;
import com.dbappsecurity.prd2api.vo.TenantQuotaVO;

/**
 * 租户配额服务
 */
public interface TenantQuotaService {

    /**
     * 校验并占用一次上传额度（原子更新，防超卖）
     */
    void checkUploadQuota(String tenantId);

    /**
     * 校验租户并发任务数
     */
    void checkConcurrentQuota(String tenantId);

    /**
     * 累计 token 消耗，超限则抛出配额异常
     */
    void consumeTokens(String tenantId, long tokens);

    TenantQuotaVO getQuota(String tenantId);

    TenantQuotaVO saveQuota(TenantQuotaDTO dto);

    /**
     * 删除租户配额（管理员清理租户数据）
     */
    void deleteQuota(String tenantId);
}
