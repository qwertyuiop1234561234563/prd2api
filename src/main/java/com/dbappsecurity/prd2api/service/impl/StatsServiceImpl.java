package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.entity.TenantQuota;
import com.dbappsecurity.prd2api.mapper.PrdTaskMapper;
import com.dbappsecurity.prd2api.mapper.TenantQuotaMapper;
import com.dbappsecurity.prd2api.service.StatsService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.AdminStatsVO;
import com.dbappsecurity.prd2api.vo.TaskDailyStatVO;
import com.dbappsecurity.prd2api.vo.TaskStatsVO;
import com.dbappsecurity.prd2api.vo.TenantUsageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端统计实现：纯 SQL 聚合，避免把全量任务捞到内存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private static final int TOP_TENANT_LIMIT = 10;
    private static final int MAX_DAYS = 90;

    private final PrdTaskMapper prdTaskMapper;
    private final TenantQuotaMapper tenantQuotaMapper;

    @Override
    public AdminStatsVO overview(String tenantId, int days) {
        String scope = resolveScope(tenantId);
        int scopeDays = Math.min(Math.max(days, 1), MAX_DAYS);
        LocalDateTime startTime = LocalDate.now().minusDays(scopeDays - 1L).atStartOfDay();

        TaskStatsVO overview = prdTaskMapper.statOverview(scope);
        if (overview == null) {
            overview = new TaskStatsVO();
        }
        normalize(overview);

        List<TaskDailyStatVO> daily = prdTaskMapper.statDaily(scope, startTime);

        List<TenantUsageVO> topTenants = tenantQuotaMapper.selectList(new LambdaQueryWrapper<TenantQuota>()
                        .orderByDesc(TenantQuota::getUsedTokenCount)
                        .last("limit " + TOP_TENANT_LIMIT))
                .stream().map(TenantUsageVO::from).toList();

        AdminStatsVO result = new AdminStatsVO();
        result.setOverview(overview);
        result.setDaily(daily);
        result.setTopTenants(topTenants);
        result.setGeneratedAt(LocalDateTime.now());
        return result;
    }

    /**
     * 非管理员只能看本租户
     */
    private String resolveScope(String tenantId) {
        if (!TenantContextUtil.isAdmin()) {
            return TenantContextUtil.requireTenantId();
        }
        return StrUtil.isBlank(tenantId) ? null : tenantId.trim();
    }

    private void normalize(TaskStatsVO overview) {
        overview.setTotal(defaultLong(overview.getTotal()));
        overview.setSuccess(defaultLong(overview.getSuccess()));
        overview.setFail(defaultLong(overview.getFail()));
        overview.setCanceled(defaultLong(overview.getCanceled()));
        overview.setTodayTotal(defaultLong(overview.getTodayTotal()));
        if (overview.getAvgDurationSeconds() == null) {
            overview.setAvgDurationSeconds(0D);
        }
        long total = overview.getTotal();
        overview.setSuccessRate(total == 0
                ? 0D
                : Math.round(overview.getSuccess() * 10000D / total) / 100D);
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }
}
