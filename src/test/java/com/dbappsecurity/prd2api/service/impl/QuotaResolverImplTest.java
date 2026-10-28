package com.dbappsecurity.prd2api.service.impl;

import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.entity.SysUser;
import com.dbappsecurity.prd2api.entity.VipPlan;
import com.dbappsecurity.prd2api.mapper.PrdTaskMapper;
import com.dbappsecurity.prd2api.mapper.SysUserMapper;
import com.dbappsecurity.prd2api.mapper.UserDailyUsageMapper;
import com.dbappsecurity.prd2api.mapper.VipPlanMapper;
import com.dbappsecurity.prd2api.service.TenantQuotaService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuotaResolverImplTest {

    private TenantQuotaService tenantQuotaService;
    private SysUserMapper sysUserMapper;
    private VipPlanMapper vipPlanMapper;
    private UserDailyUsageMapper usageMapper;
    private PrdTaskMapper prdTaskMapper;
    private QuotaResolverImpl quotaResolver;

    @BeforeEach
    void setUp() {
        tenantQuotaService = mock(TenantQuotaService.class);
        sysUserMapper = mock(SysUserMapper.class);
        vipPlanMapper = mock(VipPlanMapper.class);
        usageMapper = mock(UserDailyUsageMapper.class);
        prdTaskMapper = mock(PrdTaskMapper.class);
        quotaResolver = new QuotaResolverImpl(tenantQuotaService, sysUserMapper, vipPlanMapper,
                usageMapper, prdTaskMapper);
        TenantContextUtil.set("u-1", "tenant_01", "alice", TenantContextUtil.USER_ROLE);
    }

    @AfterEach
    void tearDown() {
        TenantContextUtil.clear();
    }

    @Test
    void activeVipShouldUseUserQuotaAndSkipTenantQuota() {
        SysUser user = vipUser(LocalDateTime.now().plusDays(10));
        when(sysUserMapper.selectById("u-1")).thenReturn(user);
        when(vipPlanMapper.selectById("BASIC")).thenReturn(plan(100));
        when(usageMapper.increaseUploadUsed(eq("u-1"), any(LocalDate.class), eq(100))).thenReturn(1);

        quotaResolver.checkUploadQuota();

        verify(tenantQuotaService, never()).checkUploadQuota(anyString());
        verify(usageMapper).ensureRow(eq("u-1"), any(LocalDate.class));
    }

    @Test
    void vipUploadQuotaExceededShouldThrow429() {
        when(sysUserMapper.selectById("u-1")).thenReturn(vipUser(LocalDateTime.now().plusDays(10)));
        when(vipPlanMapper.selectById("BASIC")).thenReturn(plan(100));
        when(usageMapper.increaseUploadUsed(anyString(), any(LocalDate.class), anyInt())).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> quotaResolver.checkUploadQuota());
        assertEquals(429, ex.getCode());
    }

    @Test
    void expiredVipShouldFallbackToTenantQuota() {
        when(sysUserMapper.selectById("u-1")).thenReturn(vipUser(LocalDateTime.now().minusDays(1)));

        quotaResolver.checkUploadQuota();

        verify(tenantQuotaService).checkUploadQuota("tenant_01");
        verify(usageMapper, never()).increaseUploadUsed(anyString(), any(LocalDate.class), anyInt());
    }

    @Test
    void nonLoginUserShouldFallbackToTenantQuota() {
        TenantContextUtil.clear();
        TenantContextUtil.set("tenant_01", "anonymous", TenantContextUtil.USER_ROLE);
        when(sysUserMapper.selectByTenantAndUsername("tenant_01", "anonymous")).thenReturn(null);

        quotaResolver.checkUploadQuota();

        verify(tenantQuotaService).checkUploadQuota("tenant_01");
    }

    @Test
    void activeVipShouldConsumeUserTokenQuota() {
        when(sysUserMapper.selectById("u-1")).thenReturn(vipUser(LocalDateTime.now().plusDays(10)));
        when(vipPlanMapper.selectById("BASIC")).thenReturn(plan(100));
        when(usageMapper.increaseTokenUsed(eq("u-1"), any(LocalDate.class), eq(500L), anyLong())).thenReturn(1);

        quotaResolver.consumeTokens(500L);

        verify(tenantQuotaService, never()).consumeTokens(anyString(), anyLong());
        verify(usageMapper).increaseTokenUsed(eq("u-1"), any(LocalDate.class), eq(500L), eq(2000000L));
    }

    private SysUser vipUser(LocalDateTime expireTime) {
        SysUser user = new SysUser();
        user.setUserId("u-1");
        user.setTenantId("tenant_01");
        user.setUsername("alice");
        user.setStatus(SysUser.STATUS_ACTIVE);
        user.setVipPlanCode("BASIC");
        user.setVipExpireTime(expireTime);
        return user;
    }

    private VipPlan plan(int uploadLimit) {
        VipPlan plan = new VipPlan();
        plan.setPlanCode("BASIC");
        plan.setPlanName("基础版");
        plan.setLevel(1);
        plan.setDurationDays(30);
        plan.setStatus(1);
        plan.setDailyUploadLimit(uploadLimit);
        plan.setDailyTokenLimit(2000000L);
        plan.setConcurrentTaskLimit(5);
        plan.setMaxFileSizeMb(20);
        return plan;
    }
}
