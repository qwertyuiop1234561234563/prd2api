package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolRegistry;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.service.AssistantPlanValidatorService;
import com.dbappsecurity.prd2api.service.AssistantPlannerService;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 任务规划实现
 * <p>
 * 规划只输出 JSON，不做任何工具执行；模型输出无法解析时返回 null，让 Agent 回落到原有 ReAct 循环，
 * 保证“规划失败不影响主流程”。显式委派（提到专家/委派）不规划，避免与子 Agent 编排重复拆解。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantPlannerServiceImpl implements AssistantPlannerService {

    /** 少于两步的计划没有意义，直接当作单步任务 */
    private static final int MIN_PLAN_STEPS = 2;
    private static final int MAX_RAW_CHARS = 400;

    private static final List<String> CONNECTORS = List.of(
            "然后", "并且", "同时", "顺便", "另外", "接着", "再帮我", "分别", "还要", "之后", "最后");
    private static final List<String> DATA_HINTS = List.of(
            "任务", "额度", "文件", "会员", "状态", "进度", "统计", "数据", "上传记录");
    private static final List<String> DOC_HINTS = List.of(
            "总结", "汇总", "周报", "写一份", "整理成", "说明", "报告", "结论");
    private static final List<String> DELEGATE_HINTS = List.of("委派", "专家", "子agent", "子 agent");

    /** 计划里允许出现的非工具动作：汇总结文 */
    private static final Set<String> ANSWER_ACTIONS = Set.of("answer", "summarize", "reply", "final_answer");

    private static final String PLAN_SYSTEM = """
            你是任务规划器，只输出一个 JSON 对象，禁止输出解释、Markdown 代码块或任何多余文字。
            可用工具（只能从下列名字中选择，不能编造）：
            %s
            输出格式：{"goal":"一句话目标","steps":[{"no":1,"action":"工具名或 answer","reason":"这一步为什么必要"}]}
            规则：
            1. 复合问题拆成 2~5 步，每步只做一件事；
            2. 最后一步用 answer 表示把结果汇总成文，不需要工具；
            3. 只依赖用户给出的信息规划，不要假设任务 ID、文件名等参数。
            """;

    private static final String REPLAN_SYSTEM = """
            你是任务规划器。上一轮计划执行时出现失败，请调整计划，只输出一个 JSON 对象，禁止输出解释。
            可用工具（只能从下列名字中选择）：
            %s
            输出格式：{"goal":"一句话目标","steps":[{"no":1,"action":"工具名或 answer","reason":"这一步为什么必要"}]}
            规则：保留已成功步骤的结论，替换或跳过失败的步骤，最多 5 步。
            """;

    private final LlmInvokeService llmInvokeService;
    private final AssistantToolRegistry toolRegistry;
    private final AssistantPlanValidatorService planValidatorService;
    private final ObjectMapper objectMapper;
    private final Prd2ApiProperties properties;
    private final MetricUtil metricUtil;

    @Override
    public boolean compound(String message) {
        String compact = StrUtil.blankToDefault(message, "").replaceAll("\\s+", "").toLowerCase();
        if (compact.length() < 8) {
            return false;
        }
        // 显式委派已经由子 Agent 承担拆解，不再叠加一层计划
        if (DELEGATE_HINTS.stream().anyMatch(compact::contains)) {
            return false;
        }
        int connector = (int) CONNECTORS.stream().filter(compact::contains).count();
        int dataHints = (int) DATA_HINTS.stream().filter(compact::contains).count();
        boolean docHint = DOC_HINTS.stream().anyMatch(compact::contains);
        int questions = compact.split("[？?]").length - 1;
        return connector > 0 || dataHints >= 2 || (dataHints >= 1 && docHint) || questions >= 2;
    }

    @Override
    public Plan plan(String message) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isPlanEnabled() || !llmInvokeService.available()) {
            return null;
        }
        try {
            String raw = llmInvokeService.chat(String.format(PLAN_SYSTEM, toolCatalog()),
                    "【用户问题】" + message, "default");
            Plan plan = parse(raw, config.getPlanMaxSteps());
            if (plan == null) {
                metricUtil.agentPlan("invalid");
                log.info("计划解析失败，回落 ReAct msg={} raw={}", StrUtil.maxLength(message, 40),
                        StrUtil.maxLength(raw, 120));
                return null;
            }
            // 执行前校验：工具必须已注册、不重复、汇总步骤收尾，能修的自动修，修不了回落 ReAct
            AssistantPlanValidatorService.Repair repair = planValidatorService.validate(plan);
            if (repair.repaired() == null) {
                metricUtil.agentPlan("plan_invalid");
                log.info("计划校验不通过，回落 ReAct issues={}", String.join("；", repair.issues()));
                return null;
            }
            metricUtil.agentPlan(repair.issues().isEmpty() ? "planned" : "plan_repaired");
            return repair.repaired();
        } catch (Exception e) {
            metricUtil.agentPlan("failed");
            log.warn("计划生成失败，回落 ReAct msg={} err={}", StrUtil.maxLength(message, 40), e.getMessage());
            return null;
        }
    }

    @Override
    public Plan replan(String message, Plan previous, String failure) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (previous == null || config == null || !config.isPlanEnabled() || !llmInvokeService.available()) {
            return null;
        }
        try {
            StringBuilder prompt = new StringBuilder("【用户问题】").append(message)
                    .append("\n【上一轮计划】").append(render(previous))
                    .append("\n【失败信息】").append(StrUtil.maxLength(failure, MAX_RAW_CHARS));
            String raw = llmInvokeService.chat(String.format(REPLAN_SYSTEM, toolCatalog()),
                    prompt.toString(), "default");
            Plan plan = parse(raw, config.getPlanMaxSteps());
            if (plan == null) {
                metricUtil.agentPlan("replan_invalid");
                return null;
            }
            AssistantPlanValidatorService.Repair repair = planValidatorService.validate(plan);
            if (repair.repaired() == null) {
                metricUtil.agentPlan("replan_invalid");
                log.info("重规划校验不通过 issues={}", String.join("；", repair.issues()));
                return null;
            }
            metricUtil.agentPlan(repair.issues().isEmpty() ? "replanned" : "replan_repaired");
            return repair.repaired();
        } catch (Exception e) {
            log.warn("重规划失败 msg={}", e.getMessage());
            return null;
        }
    }

    /**
     * 把计划渲染成可读文本，注入系统提示词，让模型知道“先做什么、再做什么”
     */
    private String render(Plan plan) {
        StringBuilder text = new StringBuilder();
        if (StrUtil.isNotBlank(plan.goal())) {
            text.append("目标：").append(plan.goal()).append('\n');
        }
        for (PlanStep step : plan.steps()) {
            text.append(step.no()).append(". ").append(step.action());
            if (StrUtil.isNotBlank(step.reason())) {
                text.append("（").append(step.reason()).append("）");
            }
            text.append('\n');
        }
        return text.toString().trim();
    }

    /**
     * 工具目录：名字 + 简介，供规划器在合法工具集合内选择
     */
    private String toolCatalog() {
        StringBuilder catalog = new StringBuilder();
        for (String name : toolRegistry.names()) {
            catalog.append("- ").append(name).append("：")
                    .append(StrUtil.maxLength(toolRegistry.descriptionOf(name), 90)).append('\n');
        }
        return catalog.toString();
    }

    /**
     * 宽容解析：允许模型输出代码块或前后夹带文字，只取第一个 JSON 对象
     */
    private Plan parse(String raw, int maxSteps) {
        String json = extractJson(raw);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String goal = root.path("goal").asText("").trim();
            JsonNode array = root.path("steps");
            if (!array.isArray()) {
                return null;
            }
            int limit = Math.max(MIN_PLAN_STEPS, maxSteps);
            List<PlanStep> steps = new ArrayList<>();
            Set<String> usedActions = new LinkedHashSet<>();
            for (JsonNode node : array) {
                if (steps.size() >= limit) {
                    break;
                }
                String action = node.path("action").asText("").trim().toLowerCase();
                if (StrUtil.isBlank(action) || !validAction(action)) {
                    continue;
                }
                if (!dedupAction(usedActions, action, node.path("reason").asText(""))) {
                    continue;
                }
                String reason = StrUtil.maxLength(node.path("reason").asText("").trim(), 60);
                steps.add(new PlanStep(steps.size() + 1, action, reason));
            }
            return steps.size() < MIN_PLAN_STEPS ? null : new Plan(goal, steps);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 同一工具只允许计划出现一次，避免模型把同一个查询拆成重复步骤
     */
    private boolean dedupAction(Set<String> usedActions, String action, String reason) {
        if (ANSWER_ACTIONS.contains(action)) {
            return true;
        }
        return usedActions.add(action) || StrUtil.isBlank(reason);
    }

    private boolean validAction(String action) {
        return ANSWER_ACTIONS.contains(action) || toolRegistry.supports(action);
    }

    private String extractJson(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }
}
