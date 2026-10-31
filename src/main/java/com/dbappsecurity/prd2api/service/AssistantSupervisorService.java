package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.service.LlmInvokeService.ToolCall;

import java.util.List;

/**
 * Supervisor 编排服务：把同一轮的多个专家子任务并行分发（fan-out），
 * 等全部结束后统一汇总（fan-in），并标记缺失与数据冲突
 * <p>
 * 与父 Agent 的 ReAct 循环解耦：父 Agent 只负责决策“派给谁”，子任务调度、超时隔离、
 * token 预算分摊与结果合并全部收口在这里，避免循环逻辑越写越乱。
 */
public interface AssistantSupervisorService {

    /**
     * 并行执行一批委派调用
     *
     * @param calls        模型发起的 delegate_task 调用列表（可来自同一轮多个 tool_call）
     * @param subAgentSteps 单个子 Agent 最多决策步数
     */
    Result execute(List<ToolCall> calls, int subAgentSteps);

    /**
     * 单个专家的执行结果
     *
     * @param issue 失败或缺失原因，成功时为空
     */
    record ExpertResult(String expert, String label, boolean success, String summary,
                        String source, long latencyMs, long tokens, String issue) {
    }

    /**
     * 汇总结果
     *
     * @param wallMs   并行墙钟耗时
     * @param serialMs 各专家耗时之和（用于估算串行执行成本）
     * @param missing  失败/缺失的专家说明
     * @param conflicts 不同专家对同一指标给出不同数值的疑似冲突
     */
    record Result(List<ExpertResult> experts, long tokens, long wallMs, long serialMs,
                  boolean parallel, List<String> missing, List<String> conflicts) {

        /**
         * 交给父 Agent 的结构化汇总：保留每个专家的结论，并显式标出缺失与冲突
         */
        public String merged() {
            StringBuilder text = new StringBuilder();
            text.append("委派 ").append(experts.size()).append(" 个专家，成功 ")
                    .append(experts.stream().filter(ExpertResult::success).count()).append(" 个");
            if (parallel) {
                text.append("；并行耗时 ").append(wallMs).append("ms（串行预计 ")
                        .append(serialMs).append("ms）");
            }
            text.append("。\n");
            for (ExpertResult expert : experts) {
                text.append("- ").append(expert.label()).append("：")
                        .append(expert.success() ? expert.summary() : "失败，" + expert.issue())
                        .append('\n');
            }
            if (!missing.isEmpty()) {
                text.append("【缺失】").append(String.join("；", missing)).append('\n');
            }
            if (!conflicts.isEmpty()) {
                text.append("【疑似冲突，需上层复核】").append(String.join("；", conflicts)).append('\n');
            }
            return text.toString().trim();
        }
    }
}
