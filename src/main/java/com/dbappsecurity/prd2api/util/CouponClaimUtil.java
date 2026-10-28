package com.dbappsecurity.prd2api.util;

import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 优惠券领取的 Redis 原子操作
 * <p>
 * 库存扣减与「每人已领数量」累加放在同一个 Lua 脚本内执行，
 * 并发请求不会出现超领（库存为负）或超出每人限领的情况。
 * 库存 Key 与个人计数 Key 使用同一个 hash tag，兼容 Redis Cluster。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponClaimUtil {

    /**
     * 初始化库存：已存在则直接返回，不存在按数据库剩余量写入，避免多实例重复初始化
     */
    private static final String INIT_SCRIPT_TEXT = """
            local stock = redis.call('GET', KEYS[1])
            if stock then
              return tonumber(stock)
            end
            local remain = tonumber(ARGV[1])
            if remain < 0 then
              remain = 0
            end
            redis.call('SET', KEYS[1], remain)
            local ttl = tonumber(ARGV[2])
            if ttl > 0 then
              redis.call('EXPIRE', KEYS[1], ttl)
            end
            return remain
            """;

    /**
     * 原子领取：返回 {结果码, 剩余库存, 本次领取序号}
     * 1 成功 / -1 已领完 / -2 超出每人限领 / -3 库存未初始化
     */
    private static final String CLAIM_SCRIPT_TEXT = """
            local stock = tonumber(redis.call('GET', KEYS[1]))
            if stock == nil then
              return {-3, 0, 0}
            end
            if stock <= 0 then
              return {-1, 0, 0}
            end
            local used = tonumber(redis.call('GET', KEYS[2]) or '0')
            local limit = tonumber(ARGV[1])
            if used >= limit then
              return {-2, stock, used}
            end
            local remain = redis.call('DECR', KEYS[1])
            local claimNo = redis.call('INCR', KEYS[2])
            local ttl = tonumber(ARGV[2])
            if ttl > 0 then
              redis.call('EXPIRE', KEYS[2], ttl)
            end
            return {1, remain, claimNo}
            """;

    /**
     * 补偿回滚：数据库落库失败时把库存与个人计数还回去
     */
    private static final String ROLLBACK_SCRIPT_TEXT = """
            redis.call('INCR', KEYS[1])
            local used = tonumber(redis.call('GET', KEYS[2]) or '0')
            if used > 0 then
              redis.call('DECR', KEYS[2])
            end
            local ttl = tonumber(ARGV[1])
            if ttl > 0 then
              redis.call('EXPIRE', KEYS[1], ttl)
              redis.call('EXPIRE', KEYS[2], ttl)
            end
            return 1
            """;

    private static final RedisScript<Long> INIT_SCRIPT =
            new DefaultRedisScript<>(INIT_SCRIPT_TEXT, Long.class);
    private static final RedisScript<List> CLAIM_SCRIPT =
            new DefaultRedisScript<>(CLAIM_SCRIPT_TEXT, List.class);
    private static final RedisScript<Long> ROLLBACK_SCRIPT =
            new DefaultRedisScript<>(ROLLBACK_SCRIPT_TEXT, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 初始化库存，ttlSeconds 为 0 表示长期有效
     */
    public void initStock(Long couponId, int remainStock, long ttlSeconds) {
        try {
            stringRedisTemplate.execute(INIT_SCRIPT,
                    List.of(RedisKeyConstant.couponStock(couponId)),
                    String.valueOf(remainStock), String.valueOf(ttlSeconds));
        } catch (Exception e) {
            // 初始化失败不阻断发券，首次领取时会再次尝试；Redis 不可用时领取会明确报错
            log.warn("优惠券库存初始化失败 couponId={}", couponId, e);
        }
    }

    /**
     * 原子领取，Redis 异常时快速失败，避免绕过库存校验
     */
    @SuppressWarnings("unchecked")
    public ClaimResult claim(Long couponId, String userId, int perUserLimit, long ttlSeconds) {
        List<Long> result;
        try {
            result = stringRedisTemplate.execute(CLAIM_SCRIPT,
                    List.of(RedisKeyConstant.couponStock(couponId),
                            RedisKeyConstant.couponUserCount(couponId, userId)),
                    String.valueOf(perUserLimit), String.valueOf(ttlSeconds));
        } catch (Exception e) {
            log.error("优惠券领取脚本执行失败 couponId={} userId={}", couponId, userId, e);
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "优惠券领取繁忙，请稍后重试");
        }
        if (result == null || result.size() < 3) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "优惠券领取结果异常，请稍后重试");
        }
        long code = result.get(0);
        long remain = result.get(1);
        int claimNo = result.get(2).intValue();
        if (code == 1L) {
            return new ClaimResult(ClaimResult.Status.SUCCESS, remain, claimNo);
        }
        if (code == -1L) {
            return new ClaimResult(ClaimResult.Status.SOLD_OUT, 0, 0);
        }
        if (code == -2L) {
            return new ClaimResult(ClaimResult.Status.LIMIT_REACHED, remain, 0);
        }
        return new ClaimResult(ClaimResult.Status.NOT_INITIALIZED, 0, 0);
    }

    /**
     * 补偿回滚，失败只记录日志，由数据库唯一键兜底
     */
    public void rollback(Long couponId, String userId, long ttlSeconds) {
        try {
            stringRedisTemplate.execute(ROLLBACK_SCRIPT,
                    List.of(RedisKeyConstant.couponStock(couponId),
                            RedisKeyConstant.couponUserCount(couponId, userId)),
                    String.valueOf(ttlSeconds));
        } catch (Exception e) {
            log.error("优惠券领取回滚失败 couponId={} userId={}", couponId, userId, e);
        }
    }

    /**
     * 下架时清理库存缓存，已领取的券不受影响
     */
    public void clearStock(Long couponId) {
        try {
            stringRedisTemplate.delete(RedisKeyConstant.couponStock(couponId));
        } catch (Exception e) {
            log.warn("优惠券库存缓存清理失败 couponId={}", couponId, e);
        }
    }

    /**
     * 领取结果
     */
    public record ClaimResult(Status status, long remainStock, int claimNo) {

        public enum Status {
            /** 领取成功 */
            SUCCESS,
            /** 库存已领完 */
            SOLD_OUT,
            /** 超出每人限领 */
            LIMIT_REACHED,
            /** 库存缓存不存在，需要先初始化 */
            NOT_INITIALIZED
        }

        public boolean success() {
            return status == Status.SUCCESS;
        }
    }
}
