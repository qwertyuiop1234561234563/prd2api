package com.dbappsecurity.prd2api.service.impl;

import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.service.AssistantHotCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 热点缓存 + 单飞实现：零外部依赖，全部基于 JDK 并发原语
 * <p>
 * L1 用 ConcurrentHashMap 存带过期时间的条目，过期条目在读取时惰性删除；
 * 单飞用 CompletableFuture 做同 key 合并，领先线程执行 loader，跟随线程等待同一 future，
 * 无论并发多少，同一 key 在缓存失效瞬间只会产生一次回源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantHotCacheServiceImpl implements AssistantHotCacheService {

    /** 最大自检并发线程数，避免诊断接口本身打爆机器 */
    private static final int MAX_SIMULATE_THREADS = 100;

    private final Prd2ApiProperties properties;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Object>> flights = new ConcurrentHashMap<>();
    private final LongAdder l1Hits = new LongAdder();
    private final LongAdder l1Misses = new LongAdder();
    private final LongAdder coalesced = new LongAdder();
    private final LongAdder loads = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        if (key == null || !enabled()) {
            return null;
        }
        Entry entry = cache.get(key);
        if (entry == null) {
            l1Misses.increment();
            return null;
        }
        if (entry.expireAt() <= System.currentTimeMillis()) {
            cache.remove(key, entry);
            evictions.increment();
            l1Misses.increment();
            return null;
        }
        l1Hits.increment();
        return (T) entry.value();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Loaded<T> loadOnce(String key, Supplier<T> loader) {
        long start = System.currentTimeMillis();
        if (key == null || !enabled()) {
            loads.increment();
            return new Loaded<>(loader.get(), false, System.currentTimeMillis() - start);
        }
        T cached = get(key);
        if (cached != null) {
            return new Loaded<>(cached, true, System.currentTimeMillis() - start);
        }
        CompletableFuture<Object> future = new CompletableFuture<>();
        CompletableFuture<Object> existing = flights.putIfAbsent(key, future);
        if (existing != null) {
            // 跟随线程：同一 key 已有线程在回源，等待并复用结果，不再打下游
            coalesced.increment();
            try {
                return new Loaded<>((T) existing.get(), true, System.currentTimeMillis() - start);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待同键回源被中断", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("同键回源失败：" + rootMessage(e), e);
            }
        }
        try {
            loads.increment();
            T value = loader.get();
            future.complete(value);
            return new Loaded<>(value, false, System.currentTimeMillis() - start);
        } catch (Throwable t) {
            future.completeExceptionally(t);
            if (t instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(t.getMessage(), t);
        } finally {
            flights.remove(key, future);
        }
    }

    @Override
    public void put(String key, Object value, long ttlMillis) {
        if (key == null || value == null || ttlMillis <= 0 || !enabled()) {
            return;
        }
        int maxEntries = Math.max(1, config().getHotCacheMaxEntries());
        if (!cache.containsKey(key) && cache.size() >= maxEntries) {
            evict(maxEntries);
        }
        cache.put(key, new Entry(value, System.currentTimeMillis() + ttlMillis));
    }

    @Override
    public Stats stats() {
        Prd2ApiProperties.Agent config = config();
        return new Stats(config != null && config.isHotCacheEnabled(), cache.size(),
                l1Hits.sum(), l1Misses.sum(), coalesced.sum(), loads.sum(), evictions.sum(),
                config == null ? 0 : config.getHotCacheMaxEntries(),
                config == null ? 0 : config.getHotCacheTtlSeconds());
    }

    @Override
    public Simulation simulate(String key, int threads, int delayMs) {
        int n = Math.max(1, Math.min(MAX_SIMULATE_THREADS, threads));
        int delay = Math.max(0, Math.min(2000, delayMs));
        // 每次自检使用独立 key，避免上一轮留下的 L1 命中干扰“回源次数”的观测
        String simulateKey = "simulate:" + key + ":" + System.nanoTime();
        AtomicInteger loaderCalls = new AtomicInteger();
        AtomicInteger cacheHits = new AtomicInteger();
        AtomicInteger valueSeq = new AtomicInteger();
        ConcurrentLinkedQueue<String> values = new ConcurrentLinkedQueue<>();
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
                    Loaded<String> loaded = loadOnce(simulateKey, () -> {
                        loaderCalls.incrementAndGet();
                        sleep(delay);
                        return "payload-" + valueSeq.incrementAndGet();
                    });
                    if (loaded.hit()) {
                        cacheHits.incrementAndGet();
                    }
                    values.add(loaded.value());
                } catch (Exception e) {
                    values.add("error:" + e.getMessage());
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
        Set<String> distinct = new HashSet<>(values);
        boolean consistent = distinct.size() == 1 && !distinct.contains(null);
        log.info("单飞自检 key={} threads={} loaderCalls={} cacheHits={} elapsed={}ms consistent={}",
                key, n, loaderCalls.get(), cacheHits.get(), elapsed, consistent);
        return new Simulation(simulateKey, n, loaderCalls.get(), cacheHits.get(), elapsed, consistent);
    }

    /**
     * 淘汰：先清理过期条目；仍超出上限则按迭代顺序淘汰最旧的一批
     */
    private void evict(int maxEntries) {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().expireAt() <= now;
            if (expired) {
                evictions.increment();
            }
            return expired;
        });
        int overflow = cache.size() - maxEntries + 1;
        if (overflow <= 0) {
            return;
        }
        Iterator<String> iterator = cache.keySet().iterator();
        List<String> removed = new ArrayList<>(overflow);
        while (iterator.hasNext() && removed.size() < overflow) {
            removed.add(iterator.next());
        }
        removed.forEach(cache::remove);
        evictions.add(removed.size());
    }

    private Prd2ApiProperties.Agent config() {
        return properties.getAgent();
    }

    private boolean enabled() {
        Prd2ApiProperties.Agent config = config();
        return config != null && config.isHotCacheEnabled();
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

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    /**
     * 缓存条目：值 + 过期时间戳
     */
    private record Entry(Object value, long expireAt) {
    }
}
