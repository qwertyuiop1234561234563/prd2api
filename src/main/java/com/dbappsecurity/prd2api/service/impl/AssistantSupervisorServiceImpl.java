package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolRegistry;
import com.dbappsecurity.prd2api.config.AsyncThreadPoolConfig;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.AssistantPromptConstant;
import com.dbappsecurity.prd2api.service.AssistantSupervisorService;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import com.dbappsecurity.prd2api.service.LlmInvokeService.ToolCall;
import com.dbappsecurity.prd2api.service.LlmInvokeService.ToolChatResult;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supervisor 编排实现：多专家并行 fan-out + 结构化 fan-in
 * <p>
 * 三个工程要点：
 * 1）子 Agent 跑在独立线程池线程里，必须在工作线程显式恢复租户上下文，否则工具查询会丢身份；
 * 2）单个专家有超时与 token 预算，任何一个专家超时只标记“缺失”，不拖垮整轮对话；
 * 3）fan-in 不额外调用模型，用确定性规则合并结论并检测跨专家数值冲突，保证结果可复现。
 */
@Slf4j
@Service
public class AssistantSupervisorServiceImpl implements AssistantSupervisorService {

    private static final int MAX_SUMMARY_CHARS = 300;
    /** “指标名 + 数值 + 单位”事实抽取，用于跨专家一致性校验 */
    private static final Pattern FACT = Pattern.compile(
            "([\\u4e00-\\u9fa5A-Za-z]{2,6})[：:为约\\s]*(\\d[\\d,]*(?:\\.\\d+)?)\\s*(个|条|项|次|份|天|%)");

    private final AssistantToolRegistry toolRegistry;
    private final LlmInvokeService llmInvokeService;
    private final ObjectMapper objectMapper;
    private final Prd2ApiProperties properties;
    private final MetricUtil metricUtil;
    private final ThreadPoolTaskExecutor toolExecutor;

    public AssistantSupervisorServiceImpl(AssistantToolRegistry toolRegistry,
                                          LlmInvokeService llmInvokeService,
                                          ObjectMapper objectMapper,
                                          Prd2ApiProperties properties,
                                          MetricUtil metricUtil,
                                          @Qualifier(AsyncThreadPoolConfig.TOOL_EXECUTOR) ThreadPoolTaskExecutor toolExecutor) {
        this.toolRegistry = toolRegistry;
        this.llmInvokeService = llmInvokeService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metricUtil = metricUtil;
        this.toolExecutor = toolExecutor;
    }

    @Override
    public Result execute(List<ToolCall> calls, int subAgentSteps) {
        if (calls == null || calls.isEmpty()) {
            return new Result(List.of(), 0L, 0L, 0L, false, List.of(), List.of());
        }
        Prd2ApiProperties.Agent config = properties.getAgent();
        boolean parallel = config != null && config.isSupervisorParallel() && calls.size() > 1;
        Ctx ctx = capture();
        int steps = Math.max(1, subAgentSteps);
        long start = System.currentTimeMillis();
        List<ExpertResult> results = parallel
                ? executeParallel(calls, steps, ctx, config)
                : executeSerial(calls, steps, ctx, config);
        long wall = System.currentTimeMillis() - start;
        long serial = results.stream().mapToLong(ExpertResult::latencyMs).sum();
        long tokens = results.stream().mapToLong(ExpertResult::tokens).sum();
        List<String> missing = results.stream()
                .filter(item -> !item.success())
                .map(item -> item.label() + "：" + StrUtil.blankToDefault(item.issue(), "未返回结论"))
                .toList();
        List<String> conflicts = detectConflicts(results);
        metricUtil.agentSupervisor(parallel, wall, Math.max(0L, serial - wall));
        log.info("Supervisor 汇总 experts={} parallel={} wall={}ms serial={}ms missing={} conflicts={}",
                results.size(), parallel, wall, serial, missing.size(), conflicts.size());
        return new Result(results, tokens, wall, serial, parallel, missing, conflicts);
    }

    /**
     * 串行模式：单个专家或关闭并行时，逐个执行
     */
    private List<ExpertResult> executeSerial(List<ToolCall> calls, int steps, Ctx ctx,
                                             Prd2ApiProperties.Agent config) {
        List<ExpertResult> results = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            results.add(runOne(call, steps, ctx, config));
        }
        return results;
    }

    /**
     * 并行模式：所有专家同时下发，逐个子任务按超时等待，超时只影响自身
     */
    private List<ExpertResult> executeParallel(List<ToolCall> calls, int steps, Ctx ctx,
                                               Prd2ApiProperties.Agent config) {
        List<Future<ExpertResult>> futures = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            futures.add(toolExecutor.submit(() -> {
                // 工作线程没有请求线程的 ThreadLocal，必须显式恢复租户上下文
                TenantContextUtil.set(ctx.userId(), ctx.tenantId(), ctx.userKey(), ctx.role());
                try {
                    return runOne(call, steps, ctx, config);
                } finally {
                    TenantContextUtil.clear();
                }
            }));
        }
        long timeout = Math.max(1000L, config.getSupervisorTimeoutMs());
        List<ExpertResult> results = new ArrayList<>(calls.size());
        for (int i = 0; i < futures.size(); i++) {
            ToolCall call = calls.get(i);
            String expert = argOf(call.arguments(), "expert");
            String label = expertLabel(expert);
            try {
                results.add(futures.get(i).get(timeout, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
                log.warn("子 Agent 超时 expert={} timeout={}ms", expert, timeout);
                metricUtil.agentDelegate(expert, false);
                results.add(failed(expert, label, "子 Agent 执行超时（" + timeout + "ms），本轮未产出结论",
                        timeout));
            } catch (Exception e) {
                log.warn("子 Agent 执行异常 expert={} msg={}", expert, e.getMessage());
                metricUtil.agentDelegate(expert, false);
                results.add(failed(expert, label, "子 Agent 执行异常："
                        + StrUtil.maxLength(e.getMessage(), 80), 0L));
            }
        }
        return results;
    }

    /**
     * 单个专家：能力边界校验 → 带工具的多步决策 → 汇总事实
     */
    private ExpertResult runOne(ToolCall call, int steps, Ctx ctx, Prd2ApiProperties.Agent config) {
        String expert = argOf(call.arguments(), "expert");
        String goal = argOf(call.arguments(), "goal");
        String label = expertLabel(expert);
        long start = System.currentTimeMillis();
        List<String> expertTools = toolRegistry.expertTools(expert);
        if (expertTools.isEmpty()) {
            metricUtil.agentDelegate(expert, false);
            return failed(expert, label, "未知专家类型：" + StrUtil.blankToDefault(expert, "空")
                    + "，可用：" + toolRegistry.experts(), System.currentTimeMillis() - start);
        }
        String goalText = StrUtil.isBlank(goal) ? "按你的职责完成用户的上一个请求" : goal;
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", toolRegistry.expertPrompt(expert)
                + "\n【平台事实，仅可基于以下内容回答】\n" + AssistantPromptConstant.SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", "【子目标】" + goalText
                + "\n请调用工具完成子目标，最后用一段中文汇总事实（含具体数字与来源），不要输出 JSON。"));
        String toolsJson = toolRegistry.definitionsJson(expertTools);
        long subTokens = 0L;
        long budget = Math.max(1000L, config.getSubAgentTokenBudget());
        String summary = "";
        boolean budgetHit = false;
        List<String> trace = new ArrayList<>();
        try {
            for (int i = 0; i < steps; i++) {
                if (subTokens >= budget) {
                    budgetHit = true;
                    break;
                }
                ToolChatResult result = llmInvokeService.chatWithTools(messages, toolsJson, "default");
                subTokens += result.tokens();
                if (result.toolCalls().isEmpty()) {
                    summary = StrUtil.blankToDefault(result.content(), "").trim();
                    break;
                }
                messages.add(assistantToolCallMessage(result));
                for (ToolCall subCall : result.toolCalls()) {
                    String callId = StrUtil.blankToDefault(subCall.id(), "call_sub_" + i);
                    if (!expertTools.contains(subCall.name())) {
                        messages.add(Map.of("role", "tool", "tool_call_id", callId,
                                "content", "该工具不在你的能力范围，请只用可用工具完成子目标。"));
                        continue;
                    }
                    List<String> missing = toolRegistry.missingRequired(subCall.name(), subCall.arguments());
                    if (!missing.isEmpty()) {
                        messages.add(Map.of("role", "tool", "tool_call_id", callId,
                                "content", "缺少必填参数：" + String.join("、", missing) + "，请补全后重试。"));
                        continue;
                    }
                    AssistantToolRegistry.ToolResult toolResult =
                            toolRegistry.execute(subCall.name(), subCall.arguments());
                    trace.add(toolRegistry.title(subCall.name()) + "：" + toolResult.success());
                    messages.add(Map.of("role", "tool", "tool_call_id", callId,
                            "content", StrUtil.blankToDefault(toolResult.summary(), "无文本结果")));
                }
            }
        } catch (Exception e) {
            log.warn("子 Agent 执行失败 expert={} msg={}", expert, e.getMessage());
            metricUtil.agentDelegate(expert, false);
            return failed(expert, label, "子 Agent 执行失败：" + e.getMessage(),
                    subTokens, System.currentTimeMillis() - start);
        }
        if (StrUtil.isBlank(summary)) {
            summary = "子 Agent 已执行 " + trace.size() + " 次工具调用（" + String.join("、", trace)
                    + "），未能在步数内产出汇总结论" + (budgetHit ? "（已达子 Agent token 预算）" : "");
        } else if (budgetHit) {
            summary = summary + "（已达子 Agent token 预算，" + budget + " tokens）";
        }
        metricUtil.agentDelegate(expert, true);
        return new ExpertResult(expert, label, true, StrUtil.maxLength(summary, MAX_SUMMARY_CHARS),
                "专家子 Agent：" + label, System.currentTimeMillis() - start, subTokens, null);
    }

    /**
     * 跨专家数据一致性：同一“指标 + 单位”出现不同数值时标记为疑似冲突
     * <p>
     * 只做确定性校验、不引入额外模型调用；冲突只做提示，最终仍由父 Agent 结合证据复核。
     */
    private List<String> detectConflicts(List<ExpertResult> results) {
        Map<String, Map<String, String>> facts = new LinkedHashMap<>();
        for (ExpertResult expert : results) {
            if (!expert.success() || StrUtil.isBlank(expert.summary())) {
                continue;
            }
            Matcher matcher = FACT.matcher(expert.summary());
            while (matcher.find()) {
                String key = matcher.group(1).replaceAll("[共总已剩余可]", "") + "|" + matcher.group(3);
                facts.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(expert.label(), matcher.group(2).replace(",", ""));
            }
        }
        List<String> conflicts = new ArrayList<>();
        facts.forEach((key, values) -> {
            if (values.size() < 2) {
                return;
            }
            String first = values.values().iterator().next();
            if (values.values().stream().anyMatch(value -> !value.equals(first))) {
                StringBuilder detail = new StringBuilder();
                values.forEach((label, value) -> {
                    if (detail.length() > 0) {
                        detail.append("，");
                    }
                    detail.append(label).append('=').append(value);
                });
                conflicts.add(key.replace('|', ' ') + "：" + detail);
            }
        });
        return conflicts.stream().limit(3).toList();
    }

    private ExpertResult failed(String expert, String label, String issue, long latencyMs) {
        return failed(expert, label, issue, 0L, latencyMs);
    }

    private ExpertResult failed(String expert, String label, String issue, long tokens, long latencyMs) {
        return new ExpertResult(expert, label, false, StrUtil.maxLength(issue, MAX_SUMMARY_CHARS),
                "专家子 Agent：" + label, latencyMs, tokens, issue);
    }

    private Map<String, Object> assistantToolCallMessage(ToolChatResult result) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", StrUtil.blankToDefault(result.content(), ""));
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ToolCall call : result.toolCalls()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", call.name());
            function.put("arguments", StrUtil.blankToDefault(call.arguments(), "{}"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", StrUtil.blankToDefault(call.id(), "call_" + call.name()));
            item.put("type", "function");
            item.put("function", function);
            calls.add(item);
        }
        message.put("tool_calls", calls);
        return message;
    }

    private String expertLabel(String expert) {
        return switch (StrUtil.blankToDefault(expert, "")) {
            case "data_analyst" -> "数据分析师";
            case "doc_writer" -> "文档撰写";
            default -> StrUtil.blankToDefault(expert, "专家");
        };
    }

    private String argOf(String argumentsJson, String field) {
        try {
            return objectMapper.readTree(StrUtil.blankToDefault(argumentsJson, "{}"))
                    .path(field).asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 调用线程捕获的租户上下文快照
     */
    private Ctx capture() {
        return new Ctx(TenantContextUtil.getTenantId(), TenantContextUtil.getUserId(),
                TenantContextUtil.getUserKey(), TenantContextUtil.getRole());
    }

    private record Ctx(String tenantId, String userId, String userKey, String role) {
    }
}
