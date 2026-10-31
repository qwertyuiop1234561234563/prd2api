package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.AssistantEvalCaseConstant;
import com.dbappsecurity.prd2api.constant.AssistantEvalCaseConstant.EvalCase;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.entity.AssistantChatMessage;
import com.dbappsecurity.prd2api.service.AssistantAgentService;
import com.dbappsecurity.prd2api.service.AssistantEvalService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.AgentStepVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 评测实现：串行跑黄金集，避免并发刷 token；结果按确定性断言打分
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantEvalServiceImpl implements AssistantEvalService {

    private static final int LOCK_SECONDS = 300;

    private final AssistantAgentService agentService;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public List<EvalCase> cases() {
        return AssistantEvalCaseConstant.CASES;
    }

    @Override
    public Report run(int limit) {
        List<EvalCase> all = cases();
        int size = limit <= 0 ? all.size() : Math.min(limit, all.size());
        List<EvalCase> selected = all.subList(0, size);
        String lockKey = RedisKeyConstant.ASSISTANT_EVAL_LOCK + ":" + TenantContextUtil.requireTenantId();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "已有评测任务在运行，请稍后再试");
        }
        List<CaseResult> results = new ArrayList<>(selected.size());
        try {
            for (EvalCase evalCase : selected) {
                results.add(runOne(evalCase));
            }
        } finally {
            stringRedisTemplate.delete(lockKey);
        }
        return summarize(results);
    }

    private CaseResult runOne(EvalCase evalCase) {
        long start = System.currentTimeMillis();
        String sessionId = "eval-" + IdUtil.fastSimpleUUID();
        List<String> questions = new ArrayList<>();
        questions.add(evalCase.message());
        questions.addAll(evalCase.followUps());
        String lastQuestion = questions.get(questions.size() - 1);
        List<AssistantChatMessage> history = new ArrayList<>();
        AssistantAgentService.AgentOutcome outcome = null;
        long tokens = 0L;
        try {
            for (int i = 0; i < questions.size(); i++) {
                String question = questions.get(i);
                outcome = agentService.run(sessionId, question, new ArrayList<>(history));
                tokens += outcome.tokenUsed();
                if (i < questions.size() - 1) {
                    history.add(message(AssistantChatMessage.ROLE_USER, question));
                    history.add(message(AssistantChatMessage.ROLE_ASSISTANT, outcome.reply()));
                }
            }
        } catch (Exception e) {
            log.warn("评测用例执行失败 case={} msg={}", evalCase.id(), e.getMessage());
            return new CaseResult(evalCase.id(), evalCase.category(), evalCase.message(), false,
                    false, false, false, false, false, false, false, List.of(), "ERROR", 0L,
                    System.currentTimeMillis() - start,
                    "执行异常：" + StrUtil.maxLength(e.getMessage(), 120));
        }
        long latency = System.currentTimeMillis() - start;
        List<AgentStepVO> steps = outcome.steps();
        List<String> actualTools = steps.stream()
                .map(AgentStepVO::getToolName)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        // 真正被执行（而非进入待确认）的工具，用于判断危险工具是否被绕过护栏执行
        List<String> executedTools = steps.stream()
                .filter(step -> "TOOL".equals(step.getType()))
                .map(AgentStepVO::getToolName)
                .filter(StrUtil::isNotBlank)
                .toList();

        boolean toolHit = evalCase.expectTools().stream().allMatch(actualTools::contains)
                && evalCase.forbidTools().stream().noneMatch(executedTools::contains);
        boolean keywordHit = StrUtil.isBlank(evalCase.expectKeyword())
                || StrUtil.contains(outcome.reply(), evalCase.expectKeyword());
        boolean selfCheckHit = !evalCase.requireSelfCheck()
                || steps.stream().anyMatch(step -> "SELF_CHECK".equals(step.getType()) && step.isSuccess());
        boolean planHit = !evalCase.requirePlan()
                || steps.stream().anyMatch(step -> "PLAN".equals(step.getType()) && step.isSuccess());
        boolean criticHit = !evalCase.requireCritic()
                || steps.stream().anyMatch(step -> "CRITIC".equals(step.getType()) && step.isSuccess());
        boolean supervisorHit = !evalCase.requireSupervisor()
                || steps.stream().anyMatch(step -> "SUPERVISOR".equals(step.getType()));
        boolean policyHit = !evalCase.requirePolicy()
                || steps.stream().anyMatch(step -> "POLICY".equals(step.getType()));
        boolean passed = toolHit && keywordHit && selfCheckHit && planHit && criticHit && supervisorHit
                && policyHit;
        return new CaseResult(evalCase.id(), evalCase.category(), lastQuestion, passed,
                toolHit, keywordHit, selfCheckHit, planHit, criticHit, supervisorHit, policyHit,
                actualTools, outcome.mode(), tokens, latency, StrUtil.maxLength(outcome.reply(), 300));
    }

    private AssistantChatMessage message(String role, String content) {
        AssistantChatMessage message = new AssistantChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private Report summarize(List<CaseResult> results) {
        int passed = 0;
        int toolChecked = 0;
        int toolHitCount = 0;
        int selfCheckHitCount = 0;
        int planHitCount = 0;
        int criticHitCount = 0;
        int supervisorHitCount = 0;
        int policyHitCount = 0;
        int degraded = 0;
        long tokens = 0L;
        long latency = 0L;
        for (CaseResult result : results) {
            if (result.passed()) {
                passed++;
            }
            toolChecked++;
            if (result.toolHit()) {
                toolHitCount++;
            }
            if (result.selfCheckHit()) {
                selfCheckHitCount++;
            }
            if (result.planHit()) {
                planHitCount++;
            }
            if (result.criticHit()) {
                criticHitCount++;
            }
            if (result.supervisorHit()) {
                supervisorHitCount++;
            }
            if (result.policyHit()) {
                policyHitCount++;
            }
            if ("RULE".equals(result.mode()) || "FAQ".equals(result.mode())) {
                degraded++;
            }
            tokens += result.tokens();
            latency += result.latencyMs();
        }
        int total = results.size();
        return new Report(total, passed, ratio(passed, total), ratio(toolHitCount, toolChecked),
                ratio((int) results.stream().filter(CaseResult::keywordHit).count(), total),
                ratio(selfCheckHitCount, total), ratio(planHitCount, total),
                ratio(criticHitCount, total), ratio(supervisorHitCount, total),
                ratio(policyHitCount, total),
                degraded, ratio(tokens, total), ratio(latency, total), results);
    }

    private double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0.0D : Math.round(numerator * 10000.0D / denominator) / 10000.0D;
    }
}
