package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * 失败样本挖掘服务
 * <p>
 * 解决的问题：失败只被“反思”一次就结束了，没有沉淀成可重复执行的回归用例。
 * 这里从失败轨迹里聚簇出“工具 + 错误特征”的样本，关联当时的用户问题，
 * 存成候选回归用例，并可一键重放当前工具链，判断问题是“已修复”还是“仍然存在”。
 */
public interface AssistantFailureCaseService {

    /**
     * 从最近失败轨迹中挖掘候选用例
     */
    MineReport mine(int limit);

    /**
     * 查看已沉淀的候选用例
     */
    List<Case> candidates();

    /**
     * 用当前工具链重放候选用例，输出已修复/仍存在
     */
    RunReport run();

    /**
     * 写入外部来源（如用户点踩）产生的回归用例，返回实际写入条数
     */
    int store(List<Case> cases);

    /**
     * 候选回归用例
     * <p>
     * source 标明来源：MINE 失败轨迹挖掘 / FEEDBACK 用户点踩
     */
    record Case(String id, String question, String toolName, String arguments, String error,
                int count, String createdAt, String source) {
    }

    record MineReport(int scanned, int groups, int stored, List<Case> cases) {
    }

    record RunItem(String id, String question, String toolName, boolean fixed, String after, long latencyMs) {
    }

    record RunReport(int total, int fixed, int open, int skipped, List<RunItem> items) {
    }
}
