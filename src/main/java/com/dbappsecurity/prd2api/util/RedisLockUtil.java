package com.dbappsecurity.prd2api.util;

import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 的分布式锁工具，防止同一任务并发重复执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLockUtil {

    private final RedissonClient redissonClient;

    /**
     * 尝试加锁
     *
     * @param key          锁 key
     * @param waitSeconds  最长等待秒数，0 表示不等待
     * @param leaseSeconds 持有锁的租期，超时自动释放
     */
    public boolean tryLock(String key, long waitSeconds, long leaseSeconds) {
        RLock lock = redissonClient.getLock(key);
        try {
            return lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "获取分布式锁被中断: " + key);
        }
    }

    /**
     * 尝试加锁（看门狗模式：不指定租期，Redisson 自动续约，直到显式解锁或进程退出）
     * <p>
     * 适用于执行时间不可预估的长任务，避免固定租期到期后重复执行。
     *
     * @param key         锁 key
     * @param waitSeconds 最长等待秒数，0 表示不等待
     */
    public boolean tryLock(String key, long waitSeconds) {
        RLock lock = redissonClient.getLock(key);
        try {
            return lock.tryLock(waitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "获取分布式锁被中断: " + key);
        }
    }

    public void unlock(String key) {
        RLock lock = redissonClient.getLock(key);
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("释放分布式锁失败 key={}", key, e);
        }
    }
}
