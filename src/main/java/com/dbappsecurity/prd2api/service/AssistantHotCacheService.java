package com.dbappsecurity.prd2api.service;

import java.util.function.Supplier;

/**
 * 进程内热点缓存 + 单飞回源（single-flight）
 * <p>
 * 解决两类高并发问题：
 * 1. 热点参数重复读 Redis：一级缓存把读压力挡在 JVM 内；
 * 2. 缓存击穿：同一 key 并发未命中时只放一个线程回源，其余线程复用同一个结果，
 * 避免瞬时流量全部打到下游（工具接口 / 向量库 / 数据库）。
 */
public interface AssistantHotCacheService {

    /**
     * 读取一级缓存，未命中或已过期返回 null
     */
    <T> T get(String key);

    /**
     * 单飞回源：同 key 并发调用时只有一个线程执行 loader，其余线程等待并复用结果
     */
    <T> Loaded<T> loadOnce(String key, Supplier<T> loader);

    /**
     * 写入一级缓存
     *
     * @param ttlMillis 本地存活毫秒数
     */
    void put(String key, Object value, long ttlMillis);

    /**
     * 运行状态，用于观测命中率与合并次数
     */
    Stats stats();

    /**
     * 并发自检：N 个线程同时请求同一 key，验证下游只被调用一次
     */
    Simulation simulate(String key, int threads, int delayMs);

    /**
     * 一次读取结果
     *
     * @param hit 是否直接命中缓存或复用了其他线程的结果（true 表示未执行 loader）
     */
    record Loaded<T>(T value, boolean hit, long costMs) {
    }

    /**
     * 缓存运行指标
     */
    record Stats(boolean enabled, int size, long l1Hits, long l1Misses, long coalesced,
                 long loads, long evictions, int maxEntries, int ttlSeconds) {
    }

    /**
     * 单飞自检结果
     *
     * @param loaderCalls 实际执行 loader 的次数，理想值为 1
     * @param cacheHits   复用其他线程结果的线程数，理想值为 threads - 1
     * @param consistent  所有线程拿到的值是否一致
     */
    record Simulation(String key, int threads, int loaderCalls, int cacheHits,
                      long elapsedMs, boolean consistent) {
    }
}
