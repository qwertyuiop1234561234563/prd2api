package com.dbappsecurity.prd2api.service;

import java.util.List;
import java.util.Map;

/**
 * Agent 存量数据 PII 泄漏审计服务
 * <p>
 * 解决的问题：写入链路虽然已经做了脱敏，但历史数据、灰度期间的数据、其他链路导入的数据
 * 仍可能带着原始个人信息躺在库里，一旦被导出或再次注入模型就是实打实的泄露。
 * 这里对会话消息与工具轨迹做确定性扫描（手机号/身份证/银行卡/邮箱），给出泄漏清单，
 * 并支持一键按同一套规则回写修复；接口只返回打码后的预览，不把原始 PII 再吐一遍。
 */
public interface AssistantPiiAuditService {

    /**
     * 只读审计：不改动任何数据
     *
     * @param limit 消息与轨迹各自最多扫描条数，默认 1000，上限 5000
     */
    Report audit(int limit);

    /**
     * 一键修复：把命中的字段按 PII 规则回写为打码文本（幂等，可重复执行）
     *
     * @param limit 消息与轨迹各自最多扫描条数，默认 1000，上限 5000
     */
    Repair repair(int limit);

    /**
     * 审计报告：verdict 为 CLEAN / LEAK_FOUND / HIGH_RISK
     */
    record Report(int scannedMessages, int scannedTraces, int leakedRows, int piiHits,
                  double leakRate, List<TypeCount> typeCounts, List<Finding> findings,
                  List<String> suggestions, String verdict) {
    }

    /**
     * PII 类型计数：severity 为 HIGH / MEDIUM
     */
    record TypeCount(String type, String label, int count, String severity) {
    }

    /**
     * 单条泄漏：preview 为打码后的片段，原始值不会出现在响应里
     */
    record Finding(String source, Long rowId, String sessionId, String field,
                   Map<String, Integer> types, String preview, String severity, boolean repairable) {
    }

    /**
     * 修复结果：repairedRows 为被改写的行数，maskedFields 为被改写的字段数
     */
    record Repair(int scannedRows, int repairedRows, int maskedFields,
                  List<String> remainingSuggestions) {
    }
}
