package com.dbappsecurity.prd2api.service.impl;

import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.service.AssistantBulkheadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 并发舱壁实现：全局信号量 + 按租户信号量，公平模式避免饿死
 * <p>
 * 注意：信号量容量在首次创建时读取配置，配置热更新后需要重启或等待实例空闲后重建；
 * 这里选择不做动态缩容，避免缩容瞬间出现“许可数小于在途数”的负值。
 */
@Slf4j
@Service
public class AssistantBulkheadServiceImpl implements AssistantBulkheadService {

    private static final int MAX_SIMULATE_THREADS = 100;

    private final Prd2ApiProperties properties;
    private final Semaphore global;
    private final Map<String, Semaphore> tenantSemaphores = new ConcurrentHashMap<>();
    private final LongAdder admitted = new LongAdder();
    private final LongAdder rejected = new LongAdder();

    public AssistantBulkheadServiceImpl(Prd2ApiProperties properties) {
        this.properties = properties;
        Prd2ApiProperties.Assistant config = properties.getAssistant();
        int limit = config == null ? 32 : Math.max(1, config.getBulkheadGlobalLimit());
        this.global = new Semaphore(limit, true);
    }

    @Override
    public Permit tryAcquire(String tenantId) {
        Prd2ApiProperties.Assistant config = config();
        if (config == null || !config.isBulkheadEnabled()) {
            return new Permit(true, tenantId, "未启用并发舱壁，直接放行", 0, 0, 0, 0);
        }
        int globalLimit = Math.max(1, config.getBulkheadGlobalLimit());
        int tenantLimit = Math.max(1, config.getBulkheadTenantLimit());
        long deadline = System.currentTimeMillis() + Math.max(0L, config.getBulkheadWaitMs());
        Semaphore tenant = tenantSemaphore(tenantId, tenantLimit);
        if (!acquire(global, deadline)) {
            rejected.increment();
            return new Permit(false, tenantId,
                    "全局并发已达上限 " + globalLimit + "，请稍后再试",
                    globalLimit, tenantLimit, inFlight(global, globalLimit), inFlight(tenant, tenantLimit));
        }
        if (!acquire(tenant, deadline)) {
            global.release();
            rejected.increment();
            return new Permit(false, tenantId,
                    "当前租户并发已达上限 " + tenantLimit + "，请稍后再试",
                    globalLimit, tenantLimit, inFlight(global, globalLimit), inFlight(tenant, tenantLimit));
        }
        admitted.increment();
        return new Permit(true, tenantId, "配额获取成功",
                globalLimit, tenantLimit, inFlight(global, globalLimit), inFlight(tenant, tenantLimit));
    }

    @Override
    public void release(Permit permit) {
        if (permit == null || !permit.allowed()) {
            return;
        }
        Semaphore tenant = tenantSemaphores.get(permit.tenantId());
        if (tenant != null) {
            tenant.release();
        }
        global.release();
    }

    @Override
    public Snapshot snapshot() {
        Prd2ApiProperties.Assistant config = config();
        int globalLimit = config == null ? 0 : Math.max(1, config.getBulkheadGlobalLimit());
        int tenantLimit = config == null ? 0 : Math.max(1, config.getBulkheadTenantLimit());
        Map<String, Integer> tenants = new LinkedHashMap<>();
        tenantSemaphores.forEach((tenantId, semaphore) ->
                tenants.put(tenantId, inFlight(semaphore, tenantLimit)));
        return new Snapshot(config != null && config.isBulkheadEnabled(),
                config == null ? 0 : inFlight(global, globalLimit), globalLimit, tenantLimit,
                admitted.sum(), rejected.sum(), tenants);
    }

    @Override
    public Simulation simulate(String tenantId, int threads, int holdMs, long waitMs) {
        int n = Math.max(1, Math.min(MAX_SIMULATE_THREADS, threads));
        int hold = Math.max(0, Math.min(2000, holdMs));
        long wait = Math.max(0L, Math.min(500L, waitMs));
        String tenant = tenantId == null || tenantId.isBlank() ? "simulate-tenant" : tenantId;
        Prd2ApiProperties.Assistant config = config();
        int globalLimit = config == null ? 0 : Math.max(1, config.getBulkheadGlobalLimit());
        int tenantLimit = config == null ? 0 : Math.max(1, config.getBulkheadTenantLimit());
        AtomicInteger admittedCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        long begin = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            pool.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    Permit permit = acquireForSimulate(tenant, wait);
                    if (!permit.allowed()) {
                        rejectedCount.incrementAndGet();
                        return;
                    }
                    admittedCount.incrementAndGet();
                    int current = inFlight.incrementAndGet();
                    peak.accumulateAndGet(current, Math::max);
                    sleep(hold);
                    inFlight.decrementAndGet();
                    release(permit);
                } catch (Exception e) {
                    rejectedCount.incrementAndGet();
                    log.warn("舱壁自检线程异常 msg={}", e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        long elapsed = System.currentTimeMillis() - begin;
        log.info("舱壁自检 tenant={} threads={} admitted={} rejected={} peak={} elapsed={}ms",
                tenant, n, admittedCount.get(), rejectedCount.get(), peak.get(), elapsed);
        return new Simulation(tenant, n, admittedCount.get(), rejectedCount.get(), peak.get(),
                globalLimit, tenantLimit, elapsed);
    }

    /**
     * 自检时按传入的等待时间获取配额；正常业务路径使用配置值
     */
    private Permit acquireForSimulate(String tenantId, long waitMs) {
        Prd2ApiProperties.Assistant config = config();
        if (config == null || !config.isBulkheadEnabled()) {
            int globalLimit = 0;
            return new Permit(true, tenantId, "未启用并发舱壁，直接放行", globalLimit, 0, 0, 0);
        }
        int globalLimit = Math.max(1, config.getBulkheadGlobalLimit());
        int tenantLimit = Math.max(1, config.getBulkheadTenantLimit());
        long deadline = System.currentTimeMillis() + waitMs;
        Semaphore tenant = tenantSemaphore(tenantId, tenantLimit);
        if (!acquire(global, deadline)) {
            rejected.increment();
            return new Permit(false, tenantId, "全局并发已达上限 " + globalLimit,
                    globalLimit, tenantLimit, inFlight(global, globalLimit), inFlight(tenant, tenantLimit));
        }
        if (!acquire(tenant, deadline)) {
            global.release();
            rejected.increment();
            return new Permit(false, tenantId, "当前租户并发已达上限 " + tenantLimit,
                    globalLimit, tenantLimit, inFlight(global, globalLimit), inFlight(tenant, tenantLimit));
        }
        admitted.increment();
        return new Permit(true, tenantId, "配额获取成功",
                globalLimit, tenantLimit, inFlight(global, globalLimit), inFlight(tenant, tenantLimit));
    }

    private boolean acquire(Semaphore semaphore, long deadline) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            return semaphore.tryAcquire();
        }
        try {
            return semaphore.tryAcquire(remaining, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Semaphore tenantSemaphore(String tenantId, int tenantLimit) {
        String key = tenantId == null || tenantId.isBlank() ? "-" : tenantId;
        return tenantSemaphores.computeIfAbsent(key, k -> new Semaphore(tenantLimit, true));
    }

    private int inFlight(Semaphore semaphore, int limit) {
        return Math.max(0, limit - semaphore.availablePermits());
    }

    private void sleep(int millis) {
        try {
            if (millis > 0) {
                Thread.sleep(millis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Prd2ApiProperties.Assistant config() {
        return properties.getAssistant();
    }
}
