package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.entity.SysUser;
import com.dbappsecurity.prd2api.entity.UserDailyUsage;
import com.dbappsecurity.prd2api.entity.VipPlan;
import com.dbappsecurity.prd2api.mapper.PrdTaskMapper;
import com.dbappsecurity.prd2api.mapper.SysUserMapper;
import com.dbappsecurity.prd2api.mapper.UserDailyUsageMapper;
import com.dbappsecurity.prd2api.mapper.VipPlanMapper;
import com.dbappsecurity.prd2api.service.QuotaResolver;
import com.dbappsecurity.prd2api.service.TenantQuotaService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.QuotaInfoVO;
import com.dbappsecurity.prd2api.vo.TenantQuotaVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 配额解析实现：VIP 优先，未生效回退租户配额
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaResolverImpl implements QuotaResolver {

    /** 免费用户单文件上限，与全局上传限制保持一致 */
    private static final int DEFAULT_MAX_FILE_SIZE_MB = 20;

    private final TenantQuotaService tenantQuotaService;
    private final SysUserMapper sysUserMapper;
    private final VipPlanMapper vipPlanMapper;
    private final UserDailyUsageMapper userDailyUsageMapper;
    private final PrdTaskMapper prdTaskMapper;

    @Override
    public void checkUploadQuota() {
        String tenantId = TenantContextUtil.requireTenantId();
        SysUser user = currentUser();
        VipPlan plan = resolveVipPlan(user);
        if (plan == null) {
            tenantQuotaService.checkUploadQuota(tenantId);
            return;
        }
        LocalDate today = LocalDate.now();
        userDailyUsageMapper.ensureRow(user.getUserId(), today);
        int rows = userDailyUsageMapper.increaseUploadUsed(user.getUserId(), today, plan.getDailyUploadLimit());
        if (rows == 0) {
            throw new BizException(BizErrorCodeEnum.QUOTA_EXCEEDED,
                    "VIP 每日上传次数已用尽，上限 " + plan.getDailyUploadLimit() + " 次");
        }
    }

    @Override
    public void checkConcurrentQuota() {
        String tenantId = TenantContextUtil.requireTenantId();
        String userKey = TenantContextUtil.requireUserKey();
        SysUser user = currentUser();
        VipPlan plan = resolveVipPlan(user);
        if (plan == null) {
            tenantQuotaService.checkConcurrentQuota(tenantId);
            return;
        }
        int running = prdTaskMapper.countRunningByUser(tenantId, userKey);
        if (running >= plan.getConcurrentTaskLimit()) {
            throw new BizException(BizErrorCodeEnum.QUOTA_EXCEEDED,
                    "VIP 并发任务数已达上限 " + plan.getConcurrentTaskLimit() + "，请等待已有任务完成");
        }
    }

    @Override
    public void consumeTokens(long tokens) {
        if (tokens <= 0) {
            return;
        }
        String tenantId = TenantContextUtil.requireTenantId();
        SysUser user = currentUser();
        VipPlan plan = resolveVipPlan(user);
        if (plan == null) {
            tenantQuotaService.consumeTokens(tenantId, tokens);
            return;
        }
        LocalDate today = LocalDate.now();
        userDailyUsageMapper.ensureRow(user.getUserId(), today);
        int rows = userDailyUsageMapper.increaseTokenUsed(
                user.getUserId(), today, tokens, plan.getDailyTokenLimit());
        if (rows == 0) {
            log.warn("VIP token 配额超限 userId={} plan={} need={}", user.getUserId(), plan.getPlanCode(), tokens);
            throw new BizException(BizErrorCodeEnum.QUOTA_EXCEEDED,
                    "VIP 当日 token 配额已用尽，上限 " + plan.getDailyTokenLimit());
        }
    }

    @Override
    public QuotaInfoVO currentQuota() {
        String tenantId = TenantContextUtil.requireTenantId();
        SysUser user = currentUser();
        VipPlan plan = resolveVipPlan(user);
        QuotaInfoVO vo = new QuotaInfoVO();
        if (plan == null) {
            TenantQuotaVO tenantQuota = tenantQuotaService.getQuota(tenantId);
            vo.setSource(QuotaInfoVO.SOURCE_TENANT);
            vo.setDailyUploadLimit(tenantQuota.getDailyUploadLimit());
            vo.setDailyTokenLimit(tenantQuota.getDailyTokenLimit());
            vo.setConcurrentTaskLimit(tenantQuota.getConcurrentTaskLimit());
            vo.setMaxFileSizeMb(DEFAULT_MAX_FILE_SIZE_MB);
            vo.setUsedUploadCount(tenantQuota.getUsedUploadCount());
            vo.setUsedTokenCount(tenantQuota.getUsedTokenCount());
            return vo;
        }
        UserDailyUsage usage = userDailyUsageMapper.selectOne(new LambdaQueryWrapper<UserDailyUsage>()
                .eq(UserDailyUsage::getUserId, user.getUserId())
                .eq(UserDailyUsage::getStatDate, LocalDate.now())
                .last("limit 1"));
        vo.setSource(QuotaInfoVO.SOURCE_VIP);
        vo.setPlanCode(plan.getPlanCode());
        vo.setPlanName(plan.getPlanName());
        vo.setDailyUploadLimit(plan.getDailyUploadLimit());
        vo.setDailyTokenLimit(plan.getDailyTokenLimit());
        vo.setConcurrentTaskLimit(plan.getConcurrentTaskLimit());
        vo.setMaxFileSizeMb(plan.getMaxFileSizeMb());
        vo.setUsedUploadCount(usage == null ? 0 : usage.getUploadCount());
        vo.setUsedTokenCount(usage == null ? 0L : usage.getTokenCount());
        vo.setExpireTime(user.getVipExpireTime());
        return vo;
    }

    @Override
    public int maxFileSizeMb() {
        VipPlan plan = resolveVipPlan(currentUser());
        if (plan == null || plan.getMaxFileSizeMb() == null) {
            return DEFAULT_MAX_FILE_SIZE_MB;
        }
        return plan.getMaxFileSizeMb();
    }

    /**
     * 当前登录用户；异步线程只带租户+账号时回查数据库补齐 userId
     */
    private SysUser currentUser() {
        String userId = TenantContextUtil.getUserId();
        if (StrUtil.isNotBlank(userId)) {
            return sysUserMapper.selectById(userId);
        }
        String tenantId = TenantContextUtil.getTenantId();
        String userKey = TenantContextUtil.getUserKey();
        if (StrUtil.isBlank(tenantId) || StrUtil.isBlank(userKey)) {
            return null;
        }
        return sysUserMapper.selectByTenantAndUsername(tenantId, userKey);
    }

    /**
     * 解析生效中的套餐：未开通或已过期视为无 VIP。
     * 套餐下架只影响新开通/领取，已开通用户仍按原套餐权益执行至到期。
     */
    private VipPlan resolveVipPlan(SysUser user) {
        if (user == null || StrUtil.isBlank(user.getVipPlanCode()) || user.getVipExpireTime() == null) {
            return null;
        }
        if (!user.getVipExpireTime().isAfter(LocalDateTime.now())) {
            return null;
        }
        if (!SysUser.STATUS_ACTIVE.equals(user.getStatus())) {
            return null;
        }
        return vipPlanMapper.selectById(user.getVipPlanCode());
    }
}
