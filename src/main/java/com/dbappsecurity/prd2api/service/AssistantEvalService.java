package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.constant.AssistantEvalCaseConstant.EvalCase;

import java.util.List;

/**
 * Agent 评测服务：跑黄金集，输出工具选择准确率、通过率、token 与延迟
 */
public interface AssistantEvalService {

    /**
     * 执行评测
     *
     * @param limit 最多跑多少条（0 或负数表示全部）
     */
    Report run(int limit);

    /**
     * 评测报告
     */
    record Report(int total, int passed, double passRate, double toolAccuracy,
                  double keywordAccuracy, double selfCheckAccuracy,
                  int degradedCount, double avgTokens, double avgLatencyMs,
                  List<CaseResult> cases) {
    }

    /**
     * 单条用例结果
     */
    record CaseResult(String id, String category, String message, boolean passed,
                      boolean toolHit, boolean keywordHit, boolean selfCheckHit,
                      List<String> actualTools, String mode, long tokens, long latencyMs,
                      String reply) {
    }

    /**
     * 全量用例（供前端展示评测集）
     */
    List<EvalCase> cases();
}
