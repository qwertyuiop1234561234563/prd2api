package com.dbappsecurity.prd2api.service.impl;

import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.dto.CouponCreateDTO;
import com.dbappsecurity.prd2api.entity.Coupon;
import com.dbappsecurity.prd2api.entity.UserCoupon;
import com.dbappsecurity.prd2api.entity.VipPlan;
import com.dbappsecurity.prd2api.mapper.CouponMapper;
import com.dbappsecurity.prd2api.mapper.UserCouponMapper;
import com.dbappsecurity.prd2api.mapper.VipPlanMapper;
import com.dbappsecurity.prd2api.service.VipService;
import com.dbappsecurity.prd2api.util.CouponClaimUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.CouponVO;
import com.dbappsecurity.prd2api.vo.MyVipVO;
import com.dbappsecurity.prd2api.vo.UserCouponVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponServiceImplTest {

    @Mock
    private CouponMapper couponMapper;

    @Mock
    private UserCouponMapper userCouponMapper;

    @Mock
    private VipPlanMapper vipPlanMapper;

    @Mock
    private CouponClaimUtil couponClaimUtil;

    @Mock
    private VipService vipService;

    @InjectMocks
    private CouponServiceImpl couponService;

    @BeforeEach
    void setUp() {
        TenantContextUtil.set("u1", "t1", "alice", TenantContextUtil.USER_ROLE);
    }

    @AfterEach
    void tearDown() {
        TenantContextUtil.clear();
    }

    @Test
    void shouldClaimCouponAndPersistUserCoupon() {
        Coupon coupon = activeCoupon(1L, "BASIC", 10, 2);
        VipPlan plan = plan("BASIC", 30);
        when(couponMapper.selectById(1L)).thenReturn(coupon);
        when(couponClaimUtil.claim(eq(1L), eq("u1"), eq(2), anyLong()))
                .thenReturn(new CouponClaimUtil.ClaimResult(
                        CouponClaimUtil.ClaimResult.Status.SUCCESS, 9, 1));
        when(couponMapper.increaseClaimed(1L)).thenReturn(1);
        when(userCouponMapper.insert(any(UserCoupon.class))).thenAnswer(invocation -> {
            UserCoupon saved = invocation.getArgument(0);
            saved.setId(100L);
            return 1;
        });
        when(vipPlanMapper.selectById("BASIC")).thenReturn(plan);

        UserCouponVO vo = couponService.claim(1L);

        assertEquals(100L, vo.getId());
        assertEquals("UNUSED", vo.getStatus());
        assertEquals(1, vo.getClaimNo());
        assertEquals("BASIC", vo.getPlanCode());
        verify(couponClaimUtil).initStock(eq(1L), eq(10), anyLong());
        verify(couponClaimUtil, never()).rollback(anyLong(), anyString(), anyLong());
    }

    @Test
    void shouldRejectWhenScriptReportsSoldOut() {
        when(couponMapper.selectById(1L)).thenReturn(activeCoupon(1L, "BASIC", 10, 1));
        when(couponClaimUtil.claim(eq(1L), eq("u1"), eq(1), anyLong()))
                .thenReturn(new CouponClaimUtil.ClaimResult(
                        CouponClaimUtil.ClaimResult.Status.SOLD_OUT, 0, 0));

        BizException e = assertThrows(BizException.class, () -> couponService.claim(1L));

        assertEquals(409, e.getCode(), e.getMessage());
        assertEquals("优惠券已领完", e.getMessage());
        verify(couponMapper, never()).increaseClaimed(anyLong());
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void shouldRejectWhenPerUserLimitReached() {
        when(couponMapper.selectById(1L)).thenReturn(activeCoupon(1L, "BASIC", 10, 1));
        when(couponClaimUtil.claim(eq(1L), eq("u1"), eq(1), anyLong()))
                .thenReturn(new CouponClaimUtil.ClaimResult(
                        CouponClaimUtil.ClaimResult.Status.LIMIT_REACHED, 5, 0));

        BizException e = assertThrows(BizException.class, () -> couponService.claim(1L));

        assertEquals(409, e.getCode(), e.getMessage());
        assertEquals("每张优惠券每人限领 1 张", e.getMessage());
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void shouldCompensateRedisWhenDatabaseStockUpdateFails() {
        when(couponMapper.selectById(1L)).thenReturn(activeCoupon(1L, "BASIC", 10, 1));
        when(couponClaimUtil.claim(eq(1L), eq("u1"), eq(1), anyLong()))
                .thenReturn(new CouponClaimUtil.ClaimResult(
                        CouponClaimUtil.ClaimResult.Status.SUCCESS, 9, 1));
        when(couponMapper.increaseClaimed(1L)).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> couponService.claim(1L));

        assertEquals(409, e.getCode(), e.getMessage());
        verify(couponClaimUtil).rollback(eq(1L), eq("u1"), anyLong());
        verify(couponMapper, never()).decreaseClaimed(anyLong());
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void shouldUseCouponAndActivateVip() {
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setId(10L);
        userCoupon.setCouponId(1L);
        userCoupon.setUserId("u1");
        userCoupon.setStatus(UserCoupon.STATUS_UNUSED);
        Coupon coupon = activeCoupon(1L, "BASIC", 10, 1);
        VipPlan plan = plan("BASIC", 30);
        MyVipVO expected = new MyVipVO();
        expected.setPlanCode("BASIC");

        when(userCouponMapper.selectById(10L)).thenReturn(userCoupon);
        when(userCouponMapper.markUsed(eq(10L), eq("u1"), any(LocalDateTime.class))).thenReturn(1);
        when(couponMapper.selectById(1L)).thenReturn(coupon);
        when(vipPlanMapper.selectById("BASIC")).thenReturn(plan);
        when(vipService.activateCouponVip(eq("BASIC"), eq(30), anyString())).thenReturn(expected);

        MyVipVO result = couponService.use(10L);

        assertNotNull(result);
        assertEquals("BASIC", result.getPlanCode());
        verify(vipService).activateCouponVip(eq("BASIC"), eq(30), anyString());
    }

    @Test
    void shouldNotConsumeCouponWhenPlanOffline() {
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setId(10L);
        userCoupon.setCouponId(1L);
        userCoupon.setUserId("u1");
        userCoupon.setStatus(UserCoupon.STATUS_UNUSED);
        VipPlan offline = plan("BASIC", 30);
        offline.setStatus(0);

        when(userCouponMapper.selectById(10L)).thenReturn(userCoupon);
        when(couponMapper.selectById(1L)).thenReturn(activeCoupon(1L, "BASIC", 10, 1));
        when(vipPlanMapper.selectById("BASIC")).thenReturn(offline);

        BizException e = assertThrows(BizException.class, () -> couponService.use(10L));

        assertEquals(400, e.getCode(), e.getMessage());
        verify(userCouponMapper, never()).markUsed(anyLong(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void shouldRejectUsingCouponTwice() {
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setId(10L);
        userCoupon.setCouponId(1L);
        userCoupon.setUserId("u1");
        userCoupon.setStatus(UserCoupon.STATUS_USED);
        when(userCouponMapper.selectById(10L)).thenReturn(userCoupon);

        BizException e = assertThrows(BizException.class, () -> couponService.use(10L));

        assertEquals(409, e.getCode());
        verify(userCouponMapper, never()).markUsed(anyLong(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void shouldRejectCreateWhenPerUserLimitGreaterThanStock() {
        TenantContextUtil.set("u1", "t1", "admin", TenantContextUtil.ADMIN_ROLE);
        when(vipPlanMapper.selectById("BASIC")).thenReturn(plan("BASIC", 30));
        CouponCreateDTO dto = new CouponCreateDTO();
        dto.setCouponName("新人礼");
        dto.setPlanCode("BASIC");
        dto.setTotalStock(10);
        dto.setPerUserLimit(20);

        BizException e = assertThrows(BizException.class, () -> couponService.create(dto));

        assertEquals(400, e.getCode());
        verify(couponMapper, never()).insert(any(Coupon.class));
    }

    @Test
    void shouldCreateCouponAndWarmUpStock() {
        TenantContextUtil.set("u1", "t1", "admin", TenantContextUtil.ADMIN_ROLE);
        when(vipPlanMapper.selectById("BASIC")).thenReturn(plan("BASIC", 30));
        when(couponMapper.insert(any(Coupon.class))).thenAnswer(invocation -> {
            Coupon saved = invocation.getArgument(0);
            saved.setCouponId(5L);
            return 1;
        });
        CouponCreateDTO dto = new CouponCreateDTO();
        dto.setCouponName("新人礼");
        dto.setPlanCode("BASIC");
        dto.setTotalStock(100);
        dto.setPerUserLimit(1);
        dto.setExpireDays(7);
        dto.setValidDays(30);

        CouponVO vo = couponService.create(dto);

        assertEquals(5L, vo.getCouponId());
        assertEquals(100, vo.getTotalStock());
        assertEquals(100, vo.getRemainStock());
        verify(couponClaimUtil).initStock(eq(5L), eq(100), anyLong());
    }

    @Test
    void shouldRejectCreateByNonAdmin() {
        // 发券属于运营管理操作，普通用户即使绕过 Controller 也被服务层拦截
        CouponCreateDTO dto = new CouponCreateDTO();
        dto.setCouponName("新人礼");
        dto.setPlanCode("BASIC");
        dto.setTotalStock(10);
        dto.setPerUserLimit(1);

        BizException e = assertThrows(BizException.class, () -> couponService.create(dto));

        assertEquals(403, e.getCode());
        verify(vipPlanMapper, never()).selectById(anyString());
        verify(couponMapper, never()).insert(any(Coupon.class));
    }

    @Test
    void shouldRejectOfflineByNonAdmin() {
        assertThrows(BizException.class, () -> couponService.offline(1L));
        verify(couponMapper, never()).selectById(1L);
    }

    private Coupon activeCoupon(Long couponId, String planCode, int totalStock, int perUserLimit) {
        Coupon coupon = new Coupon();
        coupon.setCouponId(couponId);
        coupon.setCouponName("测试券");
        coupon.setPlanCode(planCode);
        coupon.setDurationDays(0);
        coupon.setTotalStock(totalStock);
        coupon.setPerUserLimit(perUserLimit);
        coupon.setClaimedCount(0);
        coupon.setValidDays(0);
        coupon.setStartTime(LocalDateTime.now().minusDays(1));
        coupon.setStatus(Coupon.STATUS_ACTIVE);
        return coupon;
    }

    private VipPlan plan(String planCode, int durationDays) {
        VipPlan plan = new VipPlan();
        plan.setPlanCode(planCode);
        plan.setPlanName("基础版");
        plan.setLevel(1);
        plan.setDurationDays(durationDays);
        plan.setStatus(1);
        return plan;
    }
}
