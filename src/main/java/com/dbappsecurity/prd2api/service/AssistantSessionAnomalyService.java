package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * 会话行为异常检测服务
 * <p>
 * 解决的问题：工具健康看板看的是“单个工具”，但真正难查的是“某一次会话整体行为异常”——
 * 步数暴涨、失败率异常、同参数反复调用、延迟整体劣化。这类会话往往就是死循环或下游故障的前兆。
 * 这里用中位数 + MAD 的稳健统计找离群会话，同时保留绝对阈值兜底（失败率/重复率/步数），
 * 避免小样本下 z 分数失真。
 */
public interface AssistantSessionAnomalyService {

    /**
     * 扫描近期会话，输出异常会话清单
     *
     * @param days  统计窗口（天），1~30
     * @param limit 最多扫描轨迹条数，默认 2000，上限 5000
     */
    Scan scan(int days, int limit);

    /**
     * 扫描结果：verdict 为 NORMAL / SUSPECT / CRITICAL
     */
    record Scan(int windowDays, int sessionCount, int anomalyCount, String verdict,
                double medianSteps, double medianFailureRate, double medianDuplicateRate,
                List<SessionAnomaly> anomalies, List<String> notes) {
    }

    /**
     * 单个异常会话：score 为各指标稳健 z 分数的最大值（绝对阈值触发时记 0）
     */
    record SessionAnomaly(String sessionId, int steps, int failures, double failureRate,
                          int duplicateSteps, double duplicateRate, int distinctTools,
                          long p95LatencyMs, double score, String severity, List<String> reasons) {
    }
}
