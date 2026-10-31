package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * 工具依赖熔断服务
 * <p>
 * 已有工具健康看板只做“事后判级”，执行器在工具持续失败时仍会每轮重试，放大下游故障。
 * 这里补上运行时的熔断决策：失败率过高的工具直接快速失败，冷却后进入半开状态只放行一次探测。
 */
public interface AssistantToolCircuitService {

    /**
     * 执行前的熔断决策
     *
     * @param toolName 工具名
     * @return 是否放行及当前状态；拒绝时 reason 会回给模型作为失败原因
     */
    Decision allow(String toolName);

    /**
     * 各工具熔断状态快照
     *
     * @param refresh 是否绕过缓存重新统计
     */
    Snapshot snapshot(boolean refresh);

    /**
     * 熔断决策
     */
    record Decision(boolean allowed, String state, String reason) {
    }

    /**
     * 熔断快照
     */
    record Snapshot(int windowCalls, int minCalls, double failureRateThreshold, long cooldownSeconds,
                    List<ToolState> tools, List<String> notes) {
    }

    /**
     * 单工具状态
     */
    record ToolState(String toolName, String state, int calls, int failures, double failureRate,
                     long lastFailureAgeMs, boolean probeTaken) {
    }
}
