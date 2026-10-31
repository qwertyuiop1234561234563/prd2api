package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolRegistry;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.service.AssistantPlanValidatorService;
import com.dbappsecurity.prd2api.service.AssistantPlannerService.Plan;
import com.dbappsecurity.prd2api.service.AssistantPlannerService.PlanStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划校验实现：确定性规则，零模型调用
 * <p>
 * 规划器只做了“工具名合法 + 同工具去重 + 步数上限”，但解析失败时直接丢弃；
 * 这里补上执行前的最后一道闸：逐条检查并修复，保证注入提示词的每一步都真能跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantPlanValidatorServiceImpl implements AssistantPlanValidatorService {

    /** 计划里允许出现的非工具动作：汇总结文 */
    private static final Set<String> ANSWER_ACTIONS = Set.of("answer", "summarize", "reply", "final_answer");
    private static final int MIN_EXECUTABLE_STEPS = 2;
    private static final int MAX_REASON_CHARS = 60;

    private final AssistantToolRegistry toolRegistry;
    private final Prd2ApiProperties properties;

    @Override
    public Repair validate(Plan plan) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isPlanValidationEnabled()) {
            return new Repair(true, List.of(), plan);
        }
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            return new Repair(false, List.of("计划为空"), null);
        }
        int maxSteps = Math.max(MIN_EXECUTABLE_STEPS, config.getPlanMaxSteps());
        List<String> issues = new ArrayList<>();
        List<PlanStep> kept = new ArrayList<>();
        Set<String> usedTools = new HashSet<>();
        int answerCount = 0;
        int size = plan.steps().size();
        for (int i = 0; i < size; i++) {
            PlanStep step = plan.steps().get(i);
            String action = StrUtil.blankToDefault(step.action(), "").trim().toLowerCase();
            if (StrUtil.isBlank(action)) {
                issues.add("第 " + (i + 1) + " 步动作为空，已丢弃");
                continue;
            }
            if (ANSWER_ACTIONS.contains(action)) {
                answerCount++;
                // 只保留最后一步的汇总，提前出现的汇总会在末尾统一补
                if (i == size - 1) {
                    kept.add(step);
                }
                continue;
            }
            if (!toolRegistry.supports(action)) {
                issues.add("第 " + (i + 1) + " 步引用了未注册的工具 " + action + "，已丢弃");
                continue;
            }
            if (!usedTools.add(action)) {
                issues.add("第 " + (i + 1) + " 步与前面重复调用 " + action + "，已丢弃");
                continue;
            }
            if (kept.size() >= maxSteps - 1) {
                issues.add("第 " + (i + 1) + " 步超出计划步数上限 " + maxSteps + "，已丢弃");
                continue;
            }
            kept.add(step);
        }
        if (answerCount > 1) {
            issues.add("计划包含 " + answerCount + " 个汇总步骤，只保留最后一个");
        }
        boolean lastIsAnswer = size > 0
                && ANSWER_ACTIONS.contains(StrUtil.blankToDefault(plan.steps().get(size - 1).action(), "")
                .trim().toLowerCase());
        if (!lastIsAnswer) {
            kept.add(new PlanStep(0, "answer", "汇总前面步骤的结论"));
            issues.add(answerCount > 0 ? "汇总步骤位置不正确，已移动到末尾" : "计划缺少汇总步骤，已自动补全");
        }
        if (kept.size() < MIN_EXECUTABLE_STEPS) {
            issues.add("修复后有效步骤不足 " + MIN_EXECUTABLE_STEPS + " 步");
            return new Repair(false, issues, null);
        }
        List<PlanStep> repaired = new ArrayList<>(kept.size());
        for (int i = 0; i < kept.size(); i++) {
            PlanStep step = kept.get(i);
            String action = StrUtil.blankToDefault(step.action(), "").trim().toLowerCase();
            repaired.add(new PlanStep(i + 1, action,
                    StrUtil.maxLength(StrUtil.blankToDefault(step.reason(), ""), MAX_REASON_CHARS)));
        }
        Plan result = new Plan(StrUtil.blankToDefault(plan.goal(), "自动修复后的执行计划"), repaired);
        boolean valid = issues.isEmpty();
        if (!valid) {
            log.info("计划校验完成 issues={} goal={}", String.join("；", issues),
                    StrUtil.maxLength(plan.goal(), 40));
        }
        return new Repair(valid, issues, result);
    }
}
