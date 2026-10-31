package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.vo.AgentStepVO;

import java.util.List;

/**
 * 目标漂移检测：执行若干步后回看“这条路径还在围绕用户目标走吗”
 * <p>
 * 只做确定性的文本关联度计算，不调用大模型，零 token；
 * 判定结果可用于运行时注入纠偏提示，也可用于离线复盘。
 */
public interface AssistantGoalDriftService {

    /**
     * 评估执行路径与目标的关联度
     *
     * @param goal  用户原始目标
     * @param steps 当前已产生的 Agent 步骤
     */
    Assessment assess(String goal, List<AgentStepVO> steps);

    /**
     * 漂移评估结果
     *
     * @param verdict      FOCUSED / WEAK / DRIFTED / INSUFFICIENT
     * @param focusRatio   聚焦比例（0~1），越高越围绕目标
     * @param steps        参与判定的步骤数
     * @param onTopicSteps 命中目标关键词的步骤数
     * @param offTopic     偏离目标的步骤工具名（最多 5 个）
     * @param suggestion   给运营/模型的处置建议
     */
    record Assessment(String verdict, double focusRatio, int steps, int onTopicSteps,
                      List<String> offTopic, String suggestion) {
    }
}
