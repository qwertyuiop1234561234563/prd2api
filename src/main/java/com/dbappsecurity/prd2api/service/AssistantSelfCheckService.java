package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.vo.AgentStepVO;

import java.util.List;

/**
 * 回答接地自检：用确定性规则核对模型答复是否真的基于工具/知识证据
 * <p>
 * 与提示词约束互补——提示词只能“劝”模型别编造，自检能在输出侧发现编造并触发一次重写，
 * 让幻觉率可度量、可回归。
 */
public interface AssistantSelfCheckService {

    /**
     * 校验答复
     *
     * @param question 用户问题
     * @param reply    模型答复
     * @param steps    本轮已执行的工具/知识步骤
     */
    Result check(String question, String reply, List<AgentStepVO> steps);

    /**
     * 基于证据重写答复，失败返回 null（调用方保留原答复）
     */
    String repair(String question, String reply, List<AgentStepVO> steps, List<String> issues);

    /**
     * 自检结果
     *
     * @param skipped 本轮没有工具/知识证据，无法自检（如寒暄、规则答复）
     * @param grounded 是否接地
     * @param issues 未通过原因
     */
    record Result(boolean skipped, boolean grounded, List<String> issues) {
    }
}
