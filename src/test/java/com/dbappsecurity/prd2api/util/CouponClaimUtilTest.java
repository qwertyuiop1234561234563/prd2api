package com.dbappsecurity.prd2api.util;

import com.dbappsecurity.prd2api.common.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponClaimUtilTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private CouponClaimUtil couponClaimUtil;

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnSuccessWithRemainStockAndClaimNo() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(List.of(1L, 9L, 2L));

        CouponClaimUtil.ClaimResult result = couponClaimUtil.claim(1L, "u1", 2, 0L);

        assertTrue(result.success());
        assertEquals(9L, result.remainStock());
        assertEquals(2, result.claimNo());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReportSoldOutWhenStockIsZero() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(List.of(-1L, 0L, 0L));

        CouponClaimUtil.ClaimResult result = couponClaimUtil.claim(1L, "u1", 1, 0L);

        assertFalse(result.success());
        assertEquals(CouponClaimUtil.ClaimResult.Status.SOLD_OUT, result.status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReportLimitReachedWhenUserQuotaExhausted() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(List.of(-2L, 5L, 3L));

        CouponClaimUtil.ClaimResult result = couponClaimUtil.claim(1L, "u1", 3, 0L);

        assertEquals(CouponClaimUtil.ClaimResult.Status.LIMIT_REACHED, result.status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReportNotInitializedWhenStockKeyMissing() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(List.of(-3L, 0L, 0L));

        CouponClaimUtil.ClaimResult result = couponClaimUtil.claim(1L, "u1", 1, 0L);

        assertEquals(CouponClaimUtil.ClaimResult.Status.NOT_INITIALIZED, result.status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldFailClosedWhenRedisUnavailable() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("redis down"));

        BizException e = assertThrows(BizException.class,
                () -> couponClaimUtil.claim(1L, "u1", 1, 0L));
        assertEquals(500, e.getCode());
    }
}
