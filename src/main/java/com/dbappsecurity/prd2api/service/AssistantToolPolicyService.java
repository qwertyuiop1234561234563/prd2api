package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * 工具选择经验策略（Episodic Tool Policy）
 * <p>
 * Tool RAG 只看“问题与工具描述”的静态语义，学不到“这类问题历史上实际用了哪些工具且成功”。
 * 这里把成功轨迹沉淀成经验条目，下一轮遇到相似问法时优先注入历史工具，
 * 相当于让 Agent 从自己的执行历史里学会选工具。
 */
public interface AssistantToolPolicyService {

    /**
     * 从一次成功执行中学习：记录“问法 → 实际用到的工具”
     */
    void learn(String question, List<String> tools);

    /**
     * 按历史经验给出相似问法下推荐的工具（没有命中返回空列表）
     */
    List<String> preferred(String question);
}
