package com.dbappsecurity.prd2api.util;

import com.dbappsecurity.prd2api.common.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitUtilTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private RateLimitUtil rateLimitUtil;

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectWhenScriptReturnsZero() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(0L);

        BizException e = assertThrows(BizException.class,
                () -> rateLimitUtil.check("prd2api:limit:tenant_01:user", 10));
        assertEquals(429, e.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPassWhenWithinLimit() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);

        assertDoesNotThrow(() -> rateLimitUtil.check("prd2api:limit:tenant_01:user", 10));
    }

    @Test
    void shouldSkipWhenLimitDisabled() {
        assertDoesNotThrow(() -> rateLimitUtil.check("any-key", 0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNotBlockBusinessWhenRedisFails() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> rateLimitUtil.check("prd2api:limit:tenant_01:user", 10));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPassWhenRedisReturnsNull() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(null);

        assertDoesNotThrow(() -> rateLimitUtil.check("prd2api:limit:tenant_01:user", 10),
                "脚本返回 null 时应当放行，避免限流组件误伤");
    }
}
