package com.dbappsecurity.prd2api.service;

import java.util.Map;

/**
 * 并发舱壁（Bulkhead）：限制同时在执行的问答轮数
 * <p>
 * 双层配额：全局上限保护进程与下游依赖，租户上限防止单个租户挤占全部容量（噪声邻居问题）。
 * 获取不到配额时快速失败，避免请求在队列里无限堆积把延迟放大。
 */
public interface AssistantBulkheadService {

    /**
     * 尝试获取一次执行配额，未启用舱壁或获取成功时 allowed=true
     */
    Permit tryAcquire(String tenantId);

    /**
     * 归还配额，必须与 tryAcquire 成对调用（建议放在 finally）
     */
    void release(Permit permit);

    /**
     * 当前在途配额与累计放行/拒绝次数
     */
    Snapshot snapshot();

    /**
     * 并发自检：N 个线程同时抢配额，验证租户级上限生效且不误伤其他租户
     */
    Simulation simulate(String tenantId, int threads, int holdMs, long waitMs);

    record Permit(boolean allowed, String tenantId, String reason,
                  int globalLimit, int tenantLimit, int globalInFlight, int tenantInFlight) {
    }

    record Snapshot(boolean enabled, int globalInFlight, int globalLimit, int tenantLimit,
                    long admittedTotal, long rejectedTotal, Map<String, Integer> tenants) {
    }

    record Simulation(String tenantId, int threads, int admitted, int rejected, int peakInFlight,
                      int globalLimit, int tenantLimit, long elapsedMs) {
    }
}
