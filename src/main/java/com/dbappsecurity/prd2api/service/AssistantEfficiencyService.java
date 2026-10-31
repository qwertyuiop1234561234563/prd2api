package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * Agent 会话执行效率画像服务
 * <p>
 * 解决的问题：一轮答复即使成功，也可能因为重复调用、同参数无效重试而白烧延迟与下游资源，
 * 但这类“空转”在日志里看不出来。这里按轨迹统计重复调用、无效失败重试与延迟浪费，
 * 输出可量化的浪费占比和优化建议，用于持续压低单轮成本。
 */
public interface AssistantEfficiencyService {

    /**
     * 生成会话执行效率画像
     *
     * @param sessionId 会话 ID
     * @param limit     最多扫描轨迹条数（1~200）
     */
    Profile profile(String sessionId, int limit);

    /**
     * 效率画像：wastedLatencyMs 为重复步骤（同工具同参数的非首次调用）累计耗时
     */
    record Profile(String sessionId, int steps, int duplicateSteps, double duplicateRate,
                   long totalLatencyMs, long wastedLatencyMs, int failedSteps,
                   int repeatedFailureGroups, int distinctTools,
                   List<ToolStat> tools, List<DuplicateCall> duplicates, List<String> suggestions) {
    }

    /**
     * 单工具统计：调用次数、失败次数与延迟分布
     */
    record ToolStat(String toolName, int calls, int failures, long totalLatencyMs,
                    long avgLatencyMs, long maxLatencyMs) {
    }

    /**
     * 重复调用明细：同工具 + 同参数出现多次时，除首次外均视为冗余
     */
    record DuplicateCall(String toolName, String arguments, int count, long wastedLatencyMs) {
    }
}
