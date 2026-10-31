package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.vo.AgentStepVO;

import java.util.List;

/**
 * Actor-Critic 自反思：让模型扮演评审员，按「忠实 / 完整 / 可执行」三个维度给答复打分，
 * 低于阈值时把评审意见回灌给 Actor 重写一次
 * <p>
 * 与接地自检互补：自检是确定性规则（数值出处、来源标注），评审是语义判断（是否答到点上、
 * 是否可执行）；两者都在输出侧拦截低质量答复，而不是只在提示词里“请求”模型别出错。
 */
public interface AssistantCriticService {

    /**
     * 评审答复，证据不足或未启用时 ran=false
     */
    Review review(String question, String reply, List<AgentStepVO> steps);

    /**
     * 按评审意见重写，失败返回 null（调用方保留原答复）
     */
    Rewrite rewrite(String question, String reply, List<AgentStepVO> steps, Review review);

    /**
     * 评审结果
     *
     * @param issues 低分原因，供重写与轨迹展示
     */
    record Review(boolean ran, int faithfulness, int completeness, int actionability,
                  boolean passed, List<String> issues, long tokens) {

        public static Review skipped() {
            return new Review(false, 0, 0, 0, true, List.of(), 0L);
        }

        public int total() {
            return faithfulness + completeness + actionability;
        }
    }

    /**
     * 重写结果
     */
    record Rewrite(String reply, long tokens) {
    }
}
