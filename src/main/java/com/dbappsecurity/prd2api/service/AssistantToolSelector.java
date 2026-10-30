package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * 工具动态检索（Tool RAG）
 * <p>
 * 工具数量增长后，全量注入 10+ 个工具定义会让 prompt 变长、模型选错率上升。
 * 这里用问题向量与工具描述向量做语义匹配，只注入最相关的 top-k 个工具；
 * 核心工具（任务/额度/知识库）始终保留，避免漏选导致能力缺失。
 */
public interface AssistantToolSelector {

    /**
     * 为一轮问题挑选工具
     *
     * @param question 用户问题
     */
    Selection select(String question);

    /**
     * 挑选结果
     *
     * @param toolNames 选中的工具名
     * @param toolsJson 对应工具定义 JSON
     * @param filtered  是否真的做了裁剪（false 表示全量注入）
     * @param candidates 候选工具总数
     */
    record Selection(List<String> toolNames, String toolsJson, boolean filtered, int candidates) {
    }
}
