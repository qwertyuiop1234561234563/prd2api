package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.vo.AdminStatsVO;

/**
 * 管理端统计服务
 */
public interface StatsService {

    /**
     * 统计大盘：总览 + 近 N 天趋势 + token 消耗 Top 租户
     *
     * @param tenantId 租户ID，为空表示全租户（仅管理员可查全量）
     * @param days     趋势天数，1~90
     */
    AdminStatsVO overview(String tenantId, int days);
}
