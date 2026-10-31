package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * 任务规划服务：Plan-and-Execute 的“先规划、再执行、失败可重规划”环节
 * <p>
 * 只对复合问题生效，单步问题不额外花一次模型调用；计划以结构化步骤返回，
 * 由 Agent 编排层注入系统提示词并透出到前端轨迹，保证「计划可解释、可追踪」。
 */
public interface AssistantPlannerService {

    /**
     * 是否为复合问题（多子目标/多空档连接词/多个问句），简单问题不规划
     */
    boolean compound(String message);

    /**
     * 生成执行计划，模型不可用或解析失败时返回 null
     */
    Plan plan(String message);

    /**
     * 工具执行失败后重规划：保留已成功结论，替换或跳过失败步骤
     */
    Plan replan(String message, Plan previous, String failure);

    /**
     * 执行计划
     *
     * @param goal  一句话目标
     * @param steps 有序步骤
     */
    record Plan(String goal, List<PlanStep> steps) {
    }

    /**
     * 计划步骤
     *
     * @param no     步骤序号
     * @param action 工具名，或 answer 表示汇总结论
     * @param reason 这一步的必要性
     */
    record PlanStep(int no, String action, String reason) {
    }
}
