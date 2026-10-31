package com.dbappsecurity.prd2api.util;

import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Redis ZSET 的滑动窗口限流（纬度：租户 + 用户）
 * <p>
 * 使用 Lua 脚本把「清理过期、写入、计数、回滚」放在一次原子调用内，
 * 避免并发下多次命令交错导致计数失真。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitUtil {

    private static final long WINDOW_MILLIS = 60_000L;

    private static final String SCRIPT_TEXT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            redis.call('ZADD', key, now, member)
            local count = redis.call('ZCARD', key)
            redis.call('PEXPIRE', key, window * 2)
            if count > limit then
              redis.call('ZREM', key, member)
              return 0
            end
            return 1
            """;

    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>(SCRIPT_TEXT, Long.class);

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
        String member = now + "-" + Thread.currentThread().getId() + "-" + Math.random();
        try {
            Long allowed = stringRedisTemplate.execute(SCRIPT, List.of(key),
                    String.valueOf(now), String.valueOf(WINDOW_MILLIS),
                    String.valueOf(limitPerMinute), member);
            if (allowed != null && allowed == 0L) {
                log.warn("触发滑动窗口限流 key={} limit={}/min", key, limitPerMinute);
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

    /**
     * 非抛异常版本：返回是否放行，供工具执行层做“限流后降级”而不是整轮失败
     * <p>
     * 限流组件自身异常时放行（fail-open），避免限流故障把 Agent 一起拖垮。
     *
     * @param key            限流 key
     * @param limitPerMinute 每分钟允许次数，<=0 表示不限流
     */
    public boolean allowed(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        String member = now + "-" + Thread.currentThread().getId() + "-" + Math.random();
        try {
            Long allowed = stringRedisTemplate.execute(SCRIPT, List.of(key),
                    String.valueOf(now), String.valueOf(WINDOW_MILLIS),
                    String.valueOf(limitPerMinute), member);
            // 脚本返回 0 表示已超限；null 视作组件异常，同样放行
            boolean ok = allowed == null || allowed != 0L;
            if (!ok) {
                log.warn("工具级限流命中 key={} limit={}/min", key, limitPerMinute);
            }
            return ok;
        } catch (Exception e) {
            log.error("限流校验异常，按放行处理 key={}", key, e);
            return true;
        }
    }
}
