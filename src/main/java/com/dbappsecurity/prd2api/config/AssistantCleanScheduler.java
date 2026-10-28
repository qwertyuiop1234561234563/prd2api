package com.dbappsecurity.prd2api.config;

import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.service.AssistantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 助手会话过期清理
 * <p>
 * 多实例部署时用 Redis 锁保证同一时刻只有一个实例在删数据，避免重复全表扫描。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantCleanScheduler {

    private static final long LOCK_WAIT_SECONDS = 0L;
    private static final long LOCK_LEASE_MINUTES = 10L;

    private final AssistantService assistantService;
    private final RedissonClient redissonClient;

    @Scheduled(cron = "${prd2api.assistant.clean-cron:0 0 5 * * ?}")
    public void cleanExpiredSessions() {
        RLock lock = redissonClient.getLock(RedisKeyConstant.ASSISTANT_CLEAN_LOCK);
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_MINUTES, TimeUnit.MINUTES);
            if (!locked) {
                return;
            }
            int cleaned = assistantService.cleanExpired();
            log.info("助手会话过期清理执行完成 cleaned={}", cleaned);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("助手会话过期清理失败", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
