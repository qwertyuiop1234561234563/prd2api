package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.dto.TenantQuotaDTO;
import com.dbappsecurity.prd2api.entity.TenantQuota;
import com.dbappsecurity.prd2api.mapper.PrdTaskMapper;
import com.dbappsecurity.prd2api.mapper.TenantQuotaMapper;
import com.dbappsecurity.prd2api.service.TenantQuotaService;
import com.dbappsecurity.prd2api.vo.TenantQuotaVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

/**
 * 租户配额实现：计数走数据库原子更新，防并发超卖
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantQuotaServiceImpl implements TenantQuotaService {

    private static final int DEFAULT_DAILY_UPLOAD_LIMIT = 50;
    private static final long DEFAULT_DAILY_TOKEN_LIMIT = 1_000_000L;
    private static final int DEFAULT_CONCURRENT_TASK_LIMIT = 5;

    private final TenantQuotaMapper tenantQuotaMapper;
    private final PrdTaskMapper prdTaskMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void checkUploadQuota(String tenantId) {
        TenantQuota quota = getOrCreate(tenantId);
        int rows = tenantQuotaMapper.increaseUploadUsed(tenantId);
        if (rows == 0) {
            log.warn("租户上传配额超限 tenantId={} used={} limit={}", tenantId,
                    quota.getUsedUploadCount(), quota.getDailyUploadLimit());
            throw new BizException(BizErrorCodeEnum.QUOTA_EXCEEDED,
                    "租户每日上传次数已用尽，上限 " + quota.getDailyUploadLimit() + " 次");
        }
        try {
            stringRedisTemplate.opsForValue().increment(RedisKeyConstant.quotaDaily(tenantId));
            stringRedisTemplate.expire(RedisKeyConstant.quotaDaily(tenantId), 1, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("配额计数写入 Redis 失败 tenantId={}", tenantId, e);
        }
    }

    @Override
    public void checkConcurrentQuota(String tenantId) {
        TenantQuota quota = getOrCreate(tenantId);
        int running = prdTaskMapper.countRunningByTenant(tenantId);
        if (running >= quota.getConcurrentTaskLimit()) {
            throw new BizException(BizErrorCodeEnum.QUOTA_EXCEEDED,
                    "租户并发任务数已达上限 " + quota.getConcurrentTaskLimit() + "，请等待已有任务完成");
        }
    }

    @Override
    public void consumeTokens(String tenantId, long tokens) {
        if (tokens <= 0) {
            return;
        }
        TenantQuota quota = getOrCreate(tenantId);
        int rows = tenantQuotaMapper.increaseTokenUsed(tenantId, tokens);
        if (rows == 0) {
            log.warn("租户 token 配额超限 tenantId={} used={} limit={} need={}", tenantId,
                    quota.getUsedTokenCount(), quota.getDailyTokenLimit(), tokens);
            throw new BizException(BizErrorCodeEnum.QUOTA_EXCEEDED,
                    "租户当日 token 配额已用尽，上限 " + quota.getDailyTokenLimit());
        }
        try {
            stringRedisTemplate.opsForValue().increment(RedisKeyConstant.quotaDaily(tenantId), tokens);
        } catch (Exception e) {
            log.warn("token 计数写入 Redis 失败 tenantId={}", tenantId, e);
        }
    }

    @Override
    public TenantQuotaVO getQuota(String tenantId) {
        return TenantQuotaVO.from(getOrCreate(tenantId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantQuotaVO saveQuota(TenantQuotaDTO dto) {
        TenantQuota existing = tenantQuotaMapper.selectOne(new LambdaQueryWrapper<TenantQuota>()
                .eq(TenantQuota::getTenantId, dto.getTenantId()).last("limit 1"));
        if (existing == null) {
            TenantQuota quota = new TenantQuota();
            quota.setTenantId(dto.getTenantId());
            quota.setDailyUploadLimit(dto.getDailyUploadLimit());
            quota.setDailyTokenLimit(dto.getDailyTokenLimit());
            quota.setConcurrentTaskLimit(dto.getConcurrentTaskLimit());
            quota.setUsedUploadCount(0);
            quota.setUsedTokenCount(0L);
            quota.setResetTime(nextResetTime());
            tenantQuotaMapper.insert(quota);
            return TenantQuotaVO.from(quota);
        }
        existing.setDailyUploadLimit(dto.getDailyUploadLimit());
        existing.setDailyTokenLimit(dto.getDailyTokenLimit());
        existing.setConcurrentTaskLimit(dto.getConcurrentTaskLimit());
        tenantQuotaMapper.updateById(existing);
        return TenantQuotaVO.from(existing);
    }

    @Override
    public void deleteQuota(String tenantId) {
        tenantQuotaMapper.delete(new LambdaQueryWrapper<TenantQuota>()
                .eq(TenantQuota::getTenantId, tenantId));
    }

    /**
     * 查询或初始化租户配额，并处理跨天重置
     */
    private TenantQuota getOrCreate(String tenantId) {
        TenantQuota quota = tenantQuotaMapper.selectOne(new LambdaQueryWrapper<TenantQuota>()
                .eq(TenantQuota::getTenantId, tenantId).last("limit 1"));
        if (quota == null) {
            quota = createDefault(tenantId);
        }
        resetIfNeeded(quota);
        return quota;
    }

    private TenantQuota createDefault(String tenantId) {
        TenantQuota quota = new TenantQuota();
        quota.setTenantId(tenantId);
        quota.setDailyUploadLimit(DEFAULT_DAILY_UPLOAD_LIMIT);
        quota.setDailyTokenLimit(DEFAULT_DAILY_TOKEN_LIMIT);
        quota.setConcurrentTaskLimit(DEFAULT_CONCURRENT_TASK_LIMIT);
        quota.setUsedUploadCount(0);
        quota.setUsedTokenCount(0L);
        quota.setResetTime(nextResetTime());
        try {
            tenantQuotaMapper.insert(quota);
        } catch (DuplicateKeyException e) {
            // 并发初始化，重新查询即可
            return tenantQuotaMapper.selectOne(new LambdaQueryWrapper<TenantQuota>()
                    .eq(TenantQuota::getTenantId, tenantId).last("limit 1"));
        }
        return quota;
    }

    private void resetIfNeeded(TenantQuota quota) {
        LocalDateTime resetTime = quota.getResetTime();
        if (resetTime != null && resetTime.isAfter(LocalDateTime.now())) {
            return;
        }
        LocalDateTime next = nextResetTime();
        tenantQuotaMapper.resetUsed(quota.getTenantId(), next);
        quota.setUsedUploadCount(0);
        quota.setUsedTokenCount(0L);
        quota.setResetTime(next);
        log.info("租户配额已重置 tenantId={} nextReset={}", quota.getTenantId(), next);
    }

    private LocalDateTime nextResetTime() {
        return LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.MIDNIGHT);
    }
}
