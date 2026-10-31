package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.dto.AssistantFeedbackDTO;

import java.util.List;

/**
 * 用户反馈闭环
 * <p>
 * 解决的问题：用户觉得答得不好，只能“重新问一次”，这个信号被丢掉了。
 * 这里把点赞/点踩落库，并把点踩的那一轮只读工具步骤自动转成回归用例，
 * 让用户的一次点踩变成一条可重复执行的质量资产。
 */
public interface AssistantFeedbackService {

    /**
     * 提交反馈；点踩时自动生成回归用例
     */
    Result submit(AssistantFeedbackDTO dto);

    /**
     * 反馈统计：赞踩比、原因分布、已转用例数
     */
    Stats stats();

    /**
     * 反馈结果
     */
    record Result(String feedbackId, String rating, String reason, boolean caseCreated,
                  String caseId, String note) {
    }

    /**
     * 原因分布项
     */
    record ReasonCount(String reason, int count) {
    }

    /**
     * 反馈统计
     */
    record Stats(int total, int up, int down, double downRate,
                 List<ReasonCount> reasons, int feedbackCases) {
    }
}
