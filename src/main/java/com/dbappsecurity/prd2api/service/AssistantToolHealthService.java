package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * Agent 工具健康看板服务
 * <p>
 * 解决的问题：现有的诊断与效率画像都只看单个会话，回答不了“某个工具最近一周是不是整体变慢/变差”，
 * 也无法在工具出问题时快速定位是哪一个下游。这里跨会话聚合轨迹，按工具给出成功率、P95 延迟与
 * 失败原因分布，并用固定的 SLO 阈值判级，让工具质量从“事后翻日志”变成“随时可查的看板”。
 * 所有判定都是确定性的，不调用模型。
 */
public interface AssistantToolHealthService {

    /**
     * 生成租户内当前用户的工具健康看板
     *
     * @param days  统计窗口（天），1~90
     * @param limit 最多扫描轨迹条数，默认 2000，上限 5000
     */
    Health health(int days, int limit);

    /**
     * 看板：verdict 为 HEALTHY / WATCH / DEGRADED，窗口内没有轨迹时为 NO_DATA
     */
    record Health(int windowDays, int scannedSteps, int toolCount, int unhealthyTools,
                  String verdict, double successRate, long p95LatencyMs,
                  List<ToolHealth> tools, List<ToolHealth> slowest,
                  List<ToolHealth> noisiest, List<String> suggestions) {
    }

    /**
     * 单工具健康：sloStatus 为 HEALTHY / WARN / CRITICAL / INSUFFICIENT
     */
    record ToolHealth(String toolName, int calls, int failures, double successRate,
                      long avgLatencyMs, long p95LatencyMs, long maxLatencyMs,
                      String sloStatus, List<Reason> topFailures) {
    }

    /**
     * 失败原因分布
     */
    record Reason(String category, String label, int count) {
    }
}
