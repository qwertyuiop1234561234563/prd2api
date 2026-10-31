package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * 长期记忆治理服务
 * <p>
 * 解决的问题：Agent 的记忆会越攒越多，并且出现两类典型劣化——
 * 同一主题被不同键写入互相矛盾的取值（例如“导出格式=Markdown”和“导出偏好=PDF”），
 * 以及长期不更新、不再被召回的陈旧条目。这里做确定性体检并支持一键压缩，
 * 让注入给模型的记忆保持可信、可解释。
 */
public interface AssistantMemoryGovernanceService {

    /**
     * 记忆体检：冲突、陈旧与从未命中的条目
     */
    Health health();

    /**
     * 记忆压缩：保留同主题中较新的取值，删除较旧的矛盾条目
     *
     * @param apply false 只演练不删除；true 真正删除
     */
    Compaction compact(boolean apply);

    /**
     * 体检报告：verdict 为 HEALTHY / AGING / NEEDS_COMPACT
     */
    record Health(int total, int staleCount, int hitlessCount, int conflictCount,
                  List<Conflict> conflicts, List<Fact> staleFacts, String verdict) {
    }

    /**
     * 一组矛盾记忆：keep 为保留项（较新），drop 为待删除项（较旧）
     */
    record Conflict(Long keepId, String keepKey, String keepValue,
                    Long dropId, String dropKey, String dropValue, double similarity) {
    }

    /**
     * 记忆条目摘要：ageDays 为距最近更新的天数
     */
    record Fact(Long id, String key, String value, long ageDays, int hitCount) {
    }

    /**
     * 压缩结果：applied=false 时为演练
     */
    record Compaction(boolean applied, int conflictCount, int removed, List<Long> removedIds) {
    }
}
