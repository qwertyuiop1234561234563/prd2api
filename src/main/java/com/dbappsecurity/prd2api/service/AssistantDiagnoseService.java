package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * 失败归因诊断服务
 * <p>
 * 解决的问题：Agent 一轮失败后，日志里只有零散步骤，排障只能靠人翻数据。
 * 这里把轨迹按“失败步骤 → 根因类别”做确定性归类（参数、下游、检索、模型、确认、预算），
 * 并给出对应修复建议，让运维看到“哪里坏了、该修什么”，而不是只知道“失败了”。
 */
public interface AssistantDiagnoseService {

    /**
     * 诊断指定会话
     *
     * @param sessionId 会话 ID
     * @param limit     最多扫描轨迹条数（1~200）
     */
    Report diagnose(String sessionId, int limit);

    /**
     * 诊断报告：verdict 为 HEALTHY / FAILED，causes 按失败数倒序
     */
    record Report(String sessionId, String verdict, int steps, int failedSteps,
                  long totalLatencyMs, long slowestMs, List<Cause> causes, List<Failure> failures) {
    }

    /**
     * 根因类别：同类失败聚合成一条，附修复建议
     */
    record Cause(String category, String label, int count, String suggestion) {
    }

    /**
     * 单个失败步骤的归因明细
     */
    record Failure(int stepNo, String type, String toolName, String category,
                   String reason, long latencyMs) {
    }
}
