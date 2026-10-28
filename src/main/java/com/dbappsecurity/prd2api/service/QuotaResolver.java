package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.vo.QuotaInfoVO;

/**
 * 统一配额入口
 * <p>
 * VIP 生效时使用用户级套餐额度（user_daily_usage 计数）；
 * 未开通、已过期或非登录用户回退所属租户配额（tenant_quota）。
 */
public interface QuotaResolver {

    /**
     * 校验并占用一次上传额度
     */
    void checkUploadQuota();

    /**
     * 校验并发任务数
     */
    void checkConcurrentQuota();

    /**
     * 累计 token 消耗，超限抛出配额异常
     */
    void consumeTokens(long tokens);

    /**
     * 当前请求生效的额度与今日用量
     */
    QuotaInfoVO currentQuota();

    /**
     * 当前生效的单文件大小上限（MB）
     */
    int maxFileSizeMb();
}
