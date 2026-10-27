package com.dbappsecurity.prd2api.util;

import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis ZSET 的滑动窗口限流（单机纬度：租户 + 用户）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitUtil {

    private static final long WINDOW_MILLIS = 60_000L;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 校验是否超限，超限抛 RATE_LIMITED
     *
     * @param key            限流 key
     * @param limitPerMinute 每分钟允许次数，<=0 表示不限流
     */
    public void check(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MILLIS;
        String member = now + "-" + Thread.currentThread().getId() + "-" + Math.random();
        try {
            stringRedisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);
            stringRedisTemplate.opsForZSet().add(key, member, now);
            Long count = stringRedisTemplate.opsForZSet().zCard(key);
            stringRedisTemplate.expire(key, 2, TimeUnit.MINUTES);
            if (count != null && count > limitPerMinute) {
                // 本次请求本身不占用配额
                stringRedisTemplate.opsForZSet().remove(key, member);
                log.warn("触发滑动窗口限流 key={} count={} limit={}", key, count, limitPerMinute);
                throw new BizException(BizErrorCodeEnum.RATE_LIMITED,
                        "请求过于频繁，每分钟上限 " + limitPerMinute + " 次");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 限流组件异常不阻断主流程
            log.error("限流校验异常 key={}", key, e);
        }
    }
}
