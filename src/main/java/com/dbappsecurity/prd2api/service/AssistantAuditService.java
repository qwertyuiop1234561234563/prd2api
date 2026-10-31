package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * Agent 决策审计
 * <p>
 * 解决的问题：轨迹只是“日志”，没人保证每一轮都按规矩办事。
 * 这里把一次提问的轨迹聚合成一轮，逐轮检查三条硬规则（失败必须反思、危险操作必须人工确认、
 * 知识成功必须有证据），输出合规评分、违规清单与可读时间线；
 * 同时给出各阶段耗时，回答“这一轮到底慢在哪”。
 */
public interface AssistantAuditService {

    /**
     * 审计一个会话
     */
    Report audit(String sessionId, int limit);

    /**
     * 单项检查结果
     */
    record Check(String rule, String label, int passed, int failed, String note) {
    }

    /**
     * 违规项
     */
    record Violation(String round, String rule, String detail) {
    }

    /**
     * 时间线
     */
    record TimelineItem(int stepNo, String round, String type, String tool, boolean success,
                        long latencyMs, String summary, String time) {
    }

    /**
     * 审计报告
     */
    record Report(String sessionId, int rounds, int steps, int violationCount, double score,
                  long totalLatencyMs, long slowestMs, List<Check> checks,
                  List<Violation> violations, List<TimelineItem> timeline) {
    }
}
