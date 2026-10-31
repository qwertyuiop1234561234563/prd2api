package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * 轨迹回放服务
 * <p>
 * 解决的问题：Agent 改了提示词、路由或检索参数后，没有回归手段，只能靠人肉复测。
 * 这里把历史轨迹里的“只读步骤”按原参数重新执行一遍，对比成败与结果相似度，
 * 用确定性指标发现行为漂移；写操作/危险动作一律跳过，保证回放无副作用。
 */
public interface AssistantReplayService {

    /**
     * 回放指定会话的只读步骤
     *
     * @param sessionId 会话 ID
     * @param limit     最多回放步数（1~50）
     */
    Report replay(String sessionId, int limit);

    /**
     * 单步回放结果
     */
    record Item(int stepNo, String toolName, String arguments, boolean beforeSuccess, boolean afterSuccess,
                double similarity, String verdict, String before, String after,
                long beforeLatencyMs, long afterLatencyMs, boolean cacheHit) {
    }

    /**
     * 回放报告：matched 为行为一致，drifted 为出现漂移，skippedWrites 为跳过的写操作步数
     */
    record Report(String sessionId, int total, int matched, int drifted, int skippedWrites, List<Item> items) {
    }
}
