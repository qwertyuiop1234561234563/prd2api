package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolExecutor;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolRegistry;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.AssistantActionType;
import com.dbappsecurity.prd2api.constant.AssistantIntentEnum;
import com.dbappsecurity.prd2api.constant.AssistantPromptConstant;
import com.dbappsecurity.prd2api.entity.AssistantChatMessage;
import com.dbappsecurity.prd2api.entity.AssistantUserMemory;
import com.dbappsecurity.prd2api.rag.KnowledgeBaseService;
import com.dbappsecurity.prd2api.service.AssistantActionService;
import com.dbappsecurity.prd2api.service.AssistantAgentService;
import com.dbappsecurity.prd2api.service.AssistantCancellationService;
import com.dbappsecurity.prd2api.service.AssistantCheckpointService;
import com.dbappsecurity.prd2api.service.AssistantConfirmationService;
import com.dbappsecurity.prd2api.service.AssistantContextService;
import com.dbappsecurity.prd2api.service.AssistantCriticService;
import com.dbappsecurity.prd2api.service.AssistantGoalDriftService;
import com.dbappsecurity.prd2api.service.AssistantMemoryService;
import com.dbappsecurity.prd2api.service.AssistantMemoryService.MemoryItem;
import com.dbappsecurity.prd2api.service.AssistantMemoryReflectionService;
import com.dbappsecurity.prd2api.service.AssistantModelRoutingService;
import com.dbappsecurity.prd2api.service.AssistantPlannerService;
import com.dbappsecurity.prd2api.service.AssistantPromptExperimentService;
import com.dbappsecurity.prd2api.service.AssistantReflectionService;
import com.dbappsecurity.prd2api.service.AssistantRetrievalService;
import com.dbappsecurity.prd2api.service.AssistantSelfCheckService;
import com.dbappsecurity.prd2api.service.AssistantSupervisorService;
import com.dbappsecurity.prd2api.service.AssistantToolContractService;
import com.dbappsecurity.prd2api.service.AssistantToolExampleService;
import com.dbappsecurity.prd2api.service.AssistantToolPolicyService;
import com.dbappsecurity.prd2api.service.AssistantToolSelector;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import com.dbappsecurity.prd2api.service.LlmInvokeService.ToolCall;
import com.dbappsecurity.prd2api.service.LlmInvokeService.ToolChatResult;
import com.dbappsecurity.prd2api.util.AssistantGuardUtil;
import com.dbappsecurity.prd2api.util.AssistantIntentResolver;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.PiiMaskUtil;
import com.dbappsecurity.prd2api.util.SimilarityUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.AgentStepVO;
import com.dbappsecurity.prd2api.vo.RagChunkVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 助手 Agent 实现：多步工具调用 + 长期记忆 + 知识库 + 规则降级
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantAgentServiceImpl implements AssistantAgentService {

    private static final int MAX_HISTORY_CONTENT = 400;
    private static final int MAX_STEP_ARGS = 160;
    private static final int MAX_STEP_RESULT = 300;
    private static final int MAX_SUMMARY_CHARS = 2000;

    private final LlmInvokeService llmInvokeService;
    private final AssistantToolRegistry toolRegistry;
    private final AssistantToolExecutor toolExecutor;
    private final AssistantConfirmationService confirmationService;
    private final AssistantMemoryService memoryService;
    private final AssistantContextService contextService;
    private final AssistantSelfCheckService selfCheckService;
    private final AssistantToolSelector toolSelector;
    private final AssistantToolExampleService toolExampleService;
    private final AssistantModelRoutingService modelRoutingService;
    private final AssistantPromptExperimentService promptExperimentService;
    private final AssistantGoalDriftService goalDriftService;
    private final AssistantPlannerService plannerService;
    private final AssistantCancellationService cancellationService;
    private final AssistantSupervisorService supervisorService;
    private final AssistantCriticService criticService;
    private final AssistantRetrievalService retrievalService;
    private final AssistantMemoryReflectionService reflectionService;
    private final AssistantCheckpointService checkpointService;
    private final AssistantToolPolicyService toolPolicyService;
    private final AssistantToolContractService toolContractService;
    private final AssistantReflectionService failureReflectionService;
    private final MetricUtil metricUtil;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AssistantActionService actionService;
    private final AssistantIntentResolver intentResolver;
    private final Prd2ApiProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public AgentOutcome run(String sessionId, String message, List<AssistantChatMessage> history) {
        return run(sessionId, message, history, null);
    }

    @Override
    public AgentOutcome run(String sessionId, String message, List<AssistantChatMessage> history,
                            AgentListener listener) {
        AssistantIntentEnum intent = intentResolver.resolve(message);
        if (intent == AssistantIntentEnum.GREETING) {
            return new AgentOutcome(AssistantPromptConstant.GREETING_REPLY, "RULE", true, List.of(),
                    AssistantActionType.NONE, null, null, null, 0L, List.of());
        }
        Prd2ApiProperties.Agent config = properties.getAgent();
        // 输入护栏：命中提示词注入特征后，本轮所有写操作升级为人工确认
        String guardNote = config != null && config.isInjectionGuard()
                ? AssistantGuardUtil.detectInjection(message) : null;
        boolean forceConfirm = guardNote != null;
        AtomicInteger emitted = new AtomicInteger();
        // 断点续跑：用户说“继续”且存在检查点时，恢复上次未完成的任务
        AssistantCheckpointService.Snapshot resume = null;
        if (config != null && config.isCheckpointEnabled() && checkpointService.resumeIntent(message)) {
            resume = checkpointService.load(sessionId);
            if (resume != null) {
                metricUtil.agentCheckpoint("resumed", resume.done().size());
            }
        }
        AgentOutcome outcome;
        if (config == null || !config.isEnabled() || !llmInvokeService.available()) {
            outcome = fallback(intent, message);
        } else if (!llmInvokeService.toolCallingAvailable()) {
            // 模型不支持 function calling：系统查询继续走确定性规则，其余问题用知识库问答
            if (actionService.executable(intent)) {
                outcome = fallback(intent, message);
            } else {
                try {
                    outcome = knowledgeChat(message, history, config);
                } catch (Exception e) {
                    log.warn("知识库问答失败，降级规则通道 msg={}", e.getMessage());
                    outcome = fallback(intent, message);
                }
            }
        } else {
            try {
                outcome = react(sessionId, message, history, config, forceConfirm, listener, emitted, resume);
            } catch (Exception e) {
                log.warn("Agent 循环失败，降级规则通道 msg={}", e.getMessage());
                outcome = fallback(intent, message);
            }
        }
        if (guardNote != null) {
            outcome = withGuardStep(outcome, guardNote);
        }
        outcome = withMemory(message, outcome);
        persistCheckpoint(sessionId, message, resume, outcome, config);
        learnToolPolicy(message, outcome);
        // 兜底/护栏/记忆追加的步骤在循环后统一补推，保证 SSE 顺序完整
        emitRemaining(outcome, listener, emitted);
        return outcome;
    }

    private void emitRemaining(AgentOutcome outcome, AgentListener listener, AtomicInteger emitted) {
        if (listener == null) {
            return;
        }
        List<AgentStepVO> steps = outcome.steps();
        for (int i = emitted.get(); i < steps.size(); i++) {
            listener.onStep(steps.get(i));
        }
    }

    /**
     * 记录护栏命中轨迹，便于审计与前端展示
     */
    private AgentOutcome withGuardStep(AgentOutcome outcome, String rule) {
        List<AgentStepVO> steps = new ArrayList<>(outcome.steps());
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(steps.size() + 1);
        step.setType("GUARD");
        step.setToolName("prompt_guard");
        step.setTitle("输入安全检查");
        step.setArguments(StrUtil.maxLength(rule, MAX_STEP_ARGS));
        step.setResult("命中提示词注入特征，本轮写操作需人工确认后执行");
        step.setSuccess(true);
        step.setTime(LocalDateTime.now());
        steps.add(step);
        return new AgentOutcome(outcome.reply(), outcome.mode(), outcome.degraded(), steps,
                outcome.actionType(), outcome.actionLabel(), outcome.actionTarget(), outcome.actionData(),
                outcome.tokenUsed(), outcome.memorySaved());
    }

    /**
     * 不带工具的单轮问答：长期记忆 + 知识库检索结果注入，兼容不支持 tools 的模型
     */
    private AgentOutcome knowledgeChat(String message, List<AssistantChatMessage> history,
                                       Prd2ApiProperties.Agent config) {
        List<AgentStepVO> steps = new ArrayList<>();
        // 修正检索：置信度不足时自动改写查询补检，低质片段不直接喂给模型
        AssistantRetrievalService.Retrieval retrieval = retrievalService.retrieve(message,
                Math.max(1, config.getKnowledgeTopK()));
        List<RagChunkVO> hits = retrieval.chunks();
        StringBuilder prompt = new StringBuilder();
        if (!hits.isEmpty()) {
            prompt.append("【知识库检索结果】\n");
            prompt.append("（").append(retrieval.note()).append("）\n");
            for (RagChunkVO hit : hits) {
                prompt.append("- 《").append(hit.getSource()).append("》")
                        .append(StrUtil.maxLength(hit.getContent(), 500)).append('\n');
            }
            AgentStepVO step = new AgentStepVO();
            step.setStepNo(1);
            step.setType("KNOWLEDGE");
            step.setToolName("search_knowledge");
            step.setTitle(toolRegistry.title("search_knowledge"));
            step.setArguments(StrUtil.maxLength(message + "｜" + retrieval.note(), MAX_STEP_ARGS));
            step.setResult(StrUtil.maxLength(hits.get(0).getContent(), MAX_STEP_RESULT));
            step.setSource(StrUtil.blankToDefault(hits.get(0).getSource(), "平台知识库"));
            step.setSuccess(true);
            step.setTime(LocalDateTime.now());
            steps.add(step);
        }
        if (!history.isEmpty()) {
            prompt.append("【最近对话】\n");
            int from = Math.max(0, history.size() - Math.max(2, config.getMemoryInjectLimit()));
            for (int i = from; i < history.size(); i++) {
                AssistantChatMessage item = history.get(i);
                String role = AssistantChatMessage.ROLE_USER.equals(item.getRole()) ? "用户" : "助手";
                prompt.append(role).append("：")
                        .append(StrUtil.maxLength(item.getContent(), MAX_HISTORY_CONTENT)).append('\n');
            }
        }
        prompt.append("【当前问题】\n").append(message);

        List<MemoryItem> facts = memoryService.recall(message, config.getMemoryInjectLimit());
        String systemPrompt = AssistantPromptConstant.SYSTEM_PROMPT + memoryBlock(facts);
        String reply = llmInvokeService.chat(systemPrompt, prompt.toString(), "default");
        CheckResult check = applySelfCheck(message, reply, steps, null, null);
        return new AgentOutcome(check.reply(), "LLM", false, steps,
                AssistantActionType.NONE, null, null, null, check.tokens(), List.of());
    }

    /**
     * ReAct 主循环：模型决策 → 工具执行 → 结果回填 → 再决策，直到给出最终答复
     */
    private AgentOutcome react(String sessionId, String message, List<AssistantChatMessage> history,
                               Prd2ApiProperties.Agent config, boolean forceConfirm,
                               AgentListener listener, AtomicInteger emitted,
                               AssistantCheckpointService.Snapshot resume) {
        List<AgentStepVO> steps = new ArrayList<>();
        // 复杂度自适应路由：按问题复杂度选择模型档位，简单问题走轻量模型省成本、复杂问题走强模型
        AssistantModelRoutingService.Route modelRoute = modelRoutingService.route(message);
        if (config.isRouteEnabled()) {
            AgentStepVO modelStep = modelRouteStep(modelRoute);
            modelStep.setStepNo(steps.size() + 1);
            steps.add(modelStep);
            emitStep(listener, emitted, modelStep);
        }
        // 本轮开始时间：中断标记只对晚于该时间的取消生效，防止历史标记误伤
        long runStart = System.currentTimeMillis();
        List<MemoryItem> facts = memoryService.recall(message, config.getMemoryInjectLimit());
        String systemPrompt = AssistantPromptConstant.SYSTEM_PROMPT
                + AssistantPromptConstant.TOOL_PROMPT + memoryBlock(facts);
        // 上下文工程：超预算时对早期对话做摘要压缩，而不是静默丢弃
        AssistantContextService.ContextResult context =
                contextService.build(sessionId, systemPrompt, history, message);
        // 断点续跑：把上次已完成步骤与目标注入系统提示词，并在轨迹里展示恢复情况
        Map<String, AssistantCheckpointService.DoneStep> doneIndex = new LinkedHashMap<>();
        String basePrompt = context.systemPrompt();
        // 工具样例沉淀：把历史成功调用的参数格式作为 few-shot 注入，降低模型编造参数的概率
        if (config.isToolExampleEnabled()) {
            AssistantToolExampleService.Block exampleBlock = toolExampleService.block();
            if (!exampleBlock.empty()) {
                basePrompt = basePrompt + exampleBlock.text();
                log.info("工具样例注入 examples={} chars={}", exampleBlock.examples(), exampleBlock.text().length());
            }
        }
        if (resume != null) {
            for (AssistantCheckpointService.DoneStep done : resume.done()) {
                doneIndex.putIfAbsent(done.signature(), done);
            }
            basePrompt = basePrompt + resumeBlock(resume);
            AgentStepVO resumeStep = resumeStep(resume);
            resumeStep.setStepNo(steps.size() + 1);
            steps.add(resumeStep);
            emitStep(listener, emitted, resumeStep);
        }
        // 提示词 A/B：同一用户稳定命中同一变体，实验组追加更简洁的回答风格约束
        String experimentUser = StrUtil.blankToDefault(
                TenantContextUtil.getUserKey(), TenantContextUtil.getUserId());
        AssistantPromptExperimentService.Assignment experiment =
                promptExperimentService.assign(experimentUser);
        if (StrUtil.isNotBlank(experiment.suffix())) {
            basePrompt = basePrompt + experiment.suffix();
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", basePrompt));
        messages.addAll(context.messages());
        if (context.compressed()) {
            AgentStepVO contextStep = contextStep(context, config);
            contextStep.setStepNo(steps.size() + 1);
            steps.add(contextStep);
            emitStep(listener, emitted, contextStep);
        }

        // 工具动态检索：只把与本轮问题相关的工具注入 prompt
        AssistantToolSelector.Selection selection = toolSelector.select(message);
        if (selection.filtered()) {
            AgentStepVO routeStep = routeStep(selection);
            routeStep.setStepNo(steps.size() + 1);
            steps.add(routeStep);
            emitStep(listener, emitted, routeStep);
        }
        // 经验策略命中：展示“这类问法历史上用过哪些工具”
        if (selection.policyHit()) {
            AgentStepVO policyStep = policyStep(selection);
            policyStep.setStepNo(steps.size() + 1);
            steps.add(policyStep);
            emitStep(listener, emitted, policyStep);
        }
        String toolsJson = selection.toolsJson();

        // Plan-and-Execute：复合任务先出计划，再把计划注入系统提示词，模型按计划推进
        AssistantPlannerService.Plan plan = null;
        if (config.isPlanEnabled() && plannerService.compound(message)) {
            plan = plannerService.plan(message);
            if (plan != null) {
                AgentStepVO planStep = planStep("PLAN", plan, null);
                planStep.setStepNo(steps.size() + 1);
                steps.add(planStep);
                emitStep(listener, emitted, planStep);
                messages.set(0, Map.of("role", "system",
                        "content", basePrompt + planBlock(plan)));
            }
        }
        int replanned = 0;
        // 漂移纠偏只注入一次，避免每轮都提示导致模型反复自我打断
        boolean driftCorrected = false;

        long tokens = 0L;
        String actionType = AssistantActionType.NONE;
        String actionLabel = null;
        String actionTarget = null;
        Object actionData = null;
        long deadline = System.currentTimeMillis() + Math.max(1000L, config.getRunTimeoutMs());
        // 同一工具 + 相同参数重复调用的计数，用于识别死循环
        Map<String, Integer> callCounts = new LinkedHashMap<>();
        // 同一失败调用只反思一次，避免反思本身变成死循环
        Set<String> reflected = new HashSet<>();
        // 同一调用只允许契约修复重检一次，防止“修复 → 仍不合规 → 再修复”的循环
        Set<String> contractRepaired = new HashSet<>();

        for (int stepNo = 1; stepNo <= config.getMaxSteps(); stepNo++) {
            // 用户中断：在下个决策点收口，不再发起新的模型调用与工具调用
            if (cancellationService.requested(sessionId, runStart)) {
                return cancelOutcome(steps, tokens, actionType, actionLabel, actionTarget, actionData,
                        listener, emitted);
            }
            if (tokens >= config.getMaxTokenBudget()) {
                return budgetOutcome(steps, tokens, actionType, actionLabel, actionTarget, actionData,
                        "token", listener, emitted);
            }
            if (System.currentTimeMillis() > deadline) {
                return budgetOutcome(steps, tokens, actionType, actionLabel, actionTarget, actionData,
                        "deadline", listener, emitted);
            }
            if (listener != null) {
                listener.onPhase(stepNo, "THINKING");
            }
            ToolChatResult result;
            try {
                result = invokeWithFallback(messages, toolsJson, modelRoute.modelCode());
            } catch (Exception e) {
                if (!steps.isEmpty()) {
                    // 已经执行过工具（可能有副作用），不再走规则通道重复执行
                    return partialOutcome(steps, tokens, actionType, actionLabel, actionTarget, actionData, e);
                }
                throw e;
            }
            tokens += result.tokens();
            if (result.toolCalls().isEmpty()) {
                String reply = StrUtil.isBlank(result.content())
                        ? AssistantPromptConstant.FALLBACK_REPLY : result.content().trim();
                CheckResult check = applySelfCheck(message, reply, steps, listener, emitted);
                return new AgentOutcome(check.reply(), "AGENT", false, steps, actionType, actionLabel,
                        actionTarget, actionData, tokens + check.tokens(), List.of());
            }
            messages.add(assistantToolCallMessage(result));

            // 先分流：子 Agent 委派 / 参数缺失追问 / 危险操作确认 / 普通工具并行执行
            List<ToolCall> executable = new ArrayList<>();
            List<ToolCall> delegations = new ArrayList<>();
            PendingAction pending = null;
            boolean loopDetected = false;
            for (ToolCall call : result.toolCalls()) {
                String signature = call.name() + "|"
                        + StrUtil.blankToDefault(call.arguments(), "{}").replaceAll("\\s+", "");
                int repeat = callCounts.merge(signature, 1, Integer::sum);
                if (repeat > Math.max(1, config.getToolRepeatLimit())) {
                    AgentStepVO step = loopStep(call, repeat);
                    step.setStepNo(steps.size() + 1);
                    steps.add(step);
                    emitStep(listener, emitted, step);
                    messages.add(Map.of("role", "tool",
                            "tool_call_id", StrUtil.blankToDefault(call.id(), "call_loop_" + stepNo),
                            "content", "检测到重复调用（相同工具与参数已执行 " + (repeat - 1) + " 次），已阻止继续执行。"));
                    loopDetected = true;
                    continue;
                }
                // 断点续跑：与检查点中已完成的调用完全一致时直接复用结果，不再真正执行
                AssistantCheckpointService.DoneStep done = doneIndex.get(
                        AssistantCheckpointService.signature(call.name(), call.arguments()));
                if (done != null) {
                    AgentStepVO replay = replayStep(call, done);
                    replay.setStepNo(steps.size() + 1);
                    steps.add(replay);
                    emitStep(listener, emitted, replay);
                    messages.add(Map.of("role", "tool",
                            "tool_call_id", StrUtil.blankToDefault(call.id(), "call_resume_" + stepNo),
                            "content", "【断点续跑】该步骤上次已完成，直接复用结果，不要重复执行：\n"
                                    + StrUtil.blankToDefault(done.summary(), "（无摘要）")));
                    continue;
                }
                if ("delegate_task".equals(call.name()) && config.isDelegateEnabled()) {
                    delegations.add(call);
                    continue;
                }
                List<String> missing = toolRegistry.missingRequired(call.name(), call.arguments());
                if (!missing.isEmpty()) {
                    return clarifyOutcome(steps, tokens, call, missing, listener, emitted);
                }
                if (toolRegistry.requiresConfirm(call.name(), forceConfirm)) {
                    String summary = confirmSummary(call);
                    String confirmId = confirmationService.create(sessionId, call.name(), call.arguments(), summary);
                    metricUtil.agentConfirm("create", true);
                    AgentStepVO step = confirmStep(stepNo, call, confirmId, summary);
                    step.setStepNo(steps.size() + 1);
                    steps.add(step);
                    emitStep(listener, emitted, step);
                    messages.add(Map.of("role", "tool",
                            "tool_call_id", StrUtil.blankToDefault(call.id(), "call_" + stepNo),
                            "content", "该操作需要用户确认，尚未执行；已生成确认票据 " + confirmId));
                    if (pending == null) {
                        pending = new PendingAction(call, confirmId, summary);
                    }
                } else {
                    executable.add(call);
                }
            }

            // Supervisor 编排：同一轮多个专家子任务并行执行，父 Agent 只拿到结构化汇总
            boolean toolFailed = false;
            if (!delegations.isEmpty()) {
                AssistantSupervisorService.Result fanOut = supervisorService.execute(
                        delegations, config.getSubAgentSteps());
                tokens += fanOut.tokens();
                int index = 0;
                for (AssistantSupervisorService.ExpertResult expert : fanOut.experts()) {
                    ToolCall call = delegations.get(index);
                    AgentStepVO step = subAgentStep(expert);
                    step.setStepNo(steps.size() + 1);
                    steps.add(step);
                    emitStep(listener, emitted, step);
                    if (!expert.success()) {
                        toolFailed = true;
                    }
                    String content = StrUtil.blankToDefault(expert.summary(), "子 Agent 未返回结论");
                    // fan-in 汇总只挂到最后一条 tool 消息，避免同一轮重复注入相同的汇总文本
                    if (index == fanOut.experts().size() - 1 && fanOut.experts().size() > 1) {
                        content = content + "\n\n【Supervisor 汇总】\n" + fanOut.merged();
                    }
                    messages.add(Map.of("role", "tool",
                            "tool_call_id", StrUtil.blankToDefault(call.id(), "call_delegate_" + stepNo),
                            "content", content));
                    index++;
                }
                if (fanOut.parallel()) {
                    AgentStepVO step = supervisorStep(fanOut);
                    step.setStepNo(steps.size() + 1);
                    steps.add(step);
                    emitStep(listener, emitted, step);
                }
            }

            for (AssistantToolExecutor.Execution execution : toolExecutor.executeAll(executable)) {
                ToolCall call = execution.call();
                AssistantToolRegistry.ToolResult toolResult = execution.result();
                String executedArgs = call.arguments();
                long latency = execution.latencyMs();
                String contractNote = null;
                // 工具输出契约：伪成功（空检索/缺字段/原始 JSON）先修复重检，修不好就降级为如实失败
                if (config.isContractEnabled()) {
                    AssistantToolContractService.Check check =
                            toolContractService.check(call.name(), toolResult, call.arguments());
                    if (check.valid()) {
                        metricUtil.agentContract("pass");
                    } else {
                        String signature = AssistantCheckpointService.signature(
                                call.name(), call.arguments()) + "|contract";
                        boolean repairedOk = false;
                        if (check.repairCall() != null && contractRepaired.add(signature)) {
                            metricUtil.agentContract("repair");
                            AssistantToolExecutor.Execution retried =
                                    toolExecutor.executeAll(List.of(check.repairCall())).get(0);
                            AssistantToolRegistry.ToolResult retriedResult = retried.result();
                            AssistantToolContractService.Check recheck = toolContractService.check(
                                    check.repairCall().name(), retriedResult, check.repairCall().arguments());
                            contractNote = check.repairNote() + "；重检结果："
                                    + (recheck.valid() ? "通过契约" : "仍未通过");
                            if (recheck.valid()) {
                                toolResult = retriedResult;
                                executedArgs = check.repairCall().arguments();
                                latency += retried.latencyMs();
                                repairedOk = true;
                                metricUtil.agentContract("repaired");
                            }
                        }
                        if (!repairedOk) {
                            toolResult = toolContractService.degrade(call.name(), toolResult, check.violations());
                            metricUtil.agentContract("degraded");
                            contractNote = (contractNote == null ? check.repairNote() : contractNote)
                                    + "；已降级为失败，禁止基于该结果作答";
                        }
                        AgentStepVO contractStep = contractStep(call, check, repairedOk, contractNote);
                        contractStep.setStepNo(steps.size() + 1);
                        steps.add(contractStep);
                        emitStep(listener, emitted, contractStep);
                    }
                }
                // 轨迹里的参数展示实际执行的那一版（修复后为改写参数），tool_call_id 仍用原始调用 ID
                ToolCall stepCall = StrUtil.equals(executedArgs, call.arguments())
                        ? call : new ToolCall(call.id(), call.name(), executedArgs);
                AgentStepVO step = buildStep(stepNo, stepCall, toolResult, latency);
                step.setStepNo(steps.size() + 1);
                steps.add(step);
                emitStep(listener, emitted, step);
                String content = StrUtil.blankToDefault(toolResult.summary(), "工具已执行，无文本结果");
                if (contractNote != null) {
                    content = content + "\n【契约修复】" + contractNote;
                }
                if (!toolResult.success()) {
                    toolFailed = true;
                    // 失败反思：先诊断原因并给出下一步建议，再把结论回灌给模型
                    String signature = AssistantCheckpointService.signature(call.name(), call.arguments());
                    if (reflected.add(signature)) {
                        AssistantReflectionService.Lesson lesson = failureReflectionService.reflect(
                                call.name(), call.arguments(), toolResult.summary());
                        if (lesson != null) {
                            AgentStepVO reflectStep = reflectionStep(call, lesson);
                            reflectStep.setStepNo(steps.size() + 1);
                            steps.add(reflectStep);
                            emitStep(listener, emitted, reflectStep);
                            content = content + "\n【失败反思】" + lesson.text();
                        }
                    }
                }
                messages.add(Map.of("role", "tool",
                        "tool_call_id", StrUtil.blankToDefault(call.id(), "call_" + stepNo),
                        "content", maskText(content)));
                if (toolResult.success() && StrUtil.isNotBlank(toolResult.actionType())
                        && !AssistantActionType.NONE.equals(toolResult.actionType())) {
                    actionType = toolResult.actionType();
                    actionLabel = toolResult.actionLabel();
                    actionTarget = toolResult.actionTarget();
                    actionData = toolResult.data();
                }
            }

            // 目标漂移自检：执行若干步后回看路径，偏离时注入一次纠偏提示，让模型主动收敛
            if (config.isGoalDriftEnabled() && !driftCorrected) {
                AssistantGoalDriftService.Assessment drift = goalDriftService.assess(message, steps);
                if ("DRIFTED".equals(drift.verdict())) {
                    driftCorrected = true;
                    AgentStepVO driftStep = driftStep(drift);
                    driftStep.setStepNo(steps.size() + 1);
                    steps.add(driftStep);
                    emitStep(listener, emitted, driftStep);
                    messages.add(Map.of("role", "system", "content",
                            "【目标漂移告警】最近步骤与初始目标关联度偏低（聚焦比例 "
                                    + drift.focusRatio() + "）。请立即停止无关操作，围绕目标“"
                                    + StrUtil.maxLength(message, 60)
                                    + "”继续；若确实无法推进，直接说明缺口并结束本轮。"));
                }
            }

            // 计划执行失败：允许一次重规划，保留已成功步骤的结论，调整后续步骤
            if (toolFailed && plan != null && replanned < Math.max(0, config.getPlanReplanLimit())) {
                String failure = failureSummary(steps);
                AssistantPlannerService.Plan next = plannerService.replan(message, plan, failure);
                if (next != null) {
                    plan = next;
                    replanned++;
                    AgentStepVO replanStep = planStep("REPLAN", next, failure);
                    replanStep.setStepNo(steps.size() + 1);
                    steps.add(replanStep);
                    emitStep(listener, emitted, replanStep);
                    messages.set(0, Map.of("role", "system",
                            "content", basePrompt + planBlock(next)));
                }
            }

            // 有危险操作待确认：立刻返回确定性提示，避免模型误以为已执行
            if (pending != null) {
                return pendingOutcome(steps, tokens, pending);
            }
            if (loopDetected && executable.isEmpty() && delegations.isEmpty()) {
                return budgetOutcome(steps, tokens, actionType, actionLabel, actionTarget, actionData,
                        "loop", listener, emitted);
            }
        }
        // 达到步数上限：再做一次不带工具的总结，避免把原始 JSON 抛给用户
        String reply = summarize(message, steps, systemPrompt);
        CheckResult check = applySelfCheck(message, reply, steps, listener, emitted);
        return new AgentOutcome(check.reply(), "AGENT", false, steps, actionType, actionLabel,
                actionTarget, actionData, tokens + check.tokens(), List.of());
    }

    /**
     * 危险操作待确认：把确认票据透出给前端，由用户点确认后才真正执行
     */
    private AgentOutcome pendingOutcome(List<AgentStepVO> steps, long tokens, PendingAction pending) {
        String reply = "「" + toolRegistry.title(pending.call().name()) + "」属于会改变系统状态的操作，"
                + "我先不做，等你确认：" + pending.summary() + "。确认后我立刻执行。";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", pending.call().name());
        data.put("toolTitle", toolRegistry.title(pending.call().name()));
        data.put("arguments", pending.call().arguments());
        data.put("summary", pending.summary());
        data.put("risk", toolRegistry.riskOf(pending.call().name()).name());
        return new AgentOutcome(reply, "AGENT", false, steps, AssistantActionType.CONFIRM,
                "确认执行「" + toolRegistry.title(pending.call().name()) + "」", pending.confirmId(),
                data, tokens, List.of());
    }

    private String confirmSummary(ToolCall call) {
        String taskId = null;
        try {
            taskId = objectMapper.readTree(StrUtil.blankToDefault(call.arguments(), "{}"))
                    .path("taskId").asText(null);
        } catch (Exception ignored) {
            // 参数不是合法 JSON 时按原始参数展示
        }
        return "cancel_task".equals(call.name()) || "retry_task".equals(call.name())
                ? "任务 " + StrUtil.blankToDefault(taskId, "未指定") + " 将被"
                + ("cancel_task".equals(call.name()) ? "取消" : "重新排队")
                : "参数：" + StrUtil.maxLength(call.arguments(), 120);
    }

    private AgentStepVO confirmStep(int stepNo, ToolCall call, String confirmId, String summary) {
        AgentStepVO step = buildStep(stepNo, call,
                new AssistantToolRegistry.ToolResult(false, "等待用户确认：" + summary, null,
                        AssistantActionType.NONE, null, null), 0L);
        step.setType("CONFIRM");
        step.setTitle("待确认：" + toolRegistry.title(call.name()));
        step.setResult(StrUtil.maxLength(summary + "（票据 " + confirmId + "）", MAX_STEP_RESULT));
        return step;
    }

    /**
     * 待确认动作
     */
    private record PendingAction(ToolCall call, String confirmId, String summary) {
    }

    /**
     * 自检/评审后的答复与额外 token 消耗
     */
    private record CheckResult(String reply, long tokens) {
    }

    private void emitStep(AgentListener listener, AtomicInteger emitted, AgentStepVO step) {
        // 轨迹会同时进 SSE 与落库，统一在这里做 PII 脱敏，避免敏感信息外发
        maskStep(step);
        if (listener != null) {
            emitted.incrementAndGet();
            listener.onStep(step);
        }
    }

    /**
     * PII 脱敏：输出/工具结果/轨迹中的手机号、身份证、银行卡、邮箱统一打码
     */
    private String maskText(String text) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isPiiMaskEnabled() || StrUtil.isBlank(text)) {
            return text;
        }
        PiiMaskUtil.MaskedText masked = PiiMaskUtil.maskAndCount(text);
        metricUtil.agentPiiMask(masked.total());
        return masked.text();
    }

    private void maskStep(AgentStepVO step) {
        if (step == null) {
            return;
        }
        step.setTitle(maskText(step.getTitle()));
        step.setArguments(maskText(step.getArguments()));
        step.setResult(maskText(step.getResult()));
        step.setSource(maskText(step.getSource()));
    }

    /**
     * 失败反思轨迹：把“为什么失败、下一步怎么做”显式展示出来
     */
    private AgentStepVO reflectionStep(ToolCall call, AssistantReflectionService.Lesson lesson) {
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(1);
        step.setType("REFLECT");
        step.setToolName("failure_reflection");
        step.setTitle("失败反思");
        step.setArguments(StrUtil.maxLength(call.name() + "：" + StrUtil.blankToDefault(call.arguments(), "{}"),
                MAX_STEP_ARGS));
        step.setResult(StrUtil.maxLength((lesson.reused() ? "复用历史教训；" : "") + lesson.text(), MAX_STEP_RESULT));
        step.setSuccess(true);
        step.setTime(LocalDateTime.now());
        return step;
    }

    /**
     * 契约校验轨迹：暴露“工具返回了什么、违反了哪条契约、修复是否成功”
     */
    private AgentStepVO contractStep(ToolCall call, AssistantToolContractService.Check check,
                                     boolean repaired, String note) {
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(1);
        step.setType("CONTRACT");
        step.setToolName("tool_contract");
        step.setTitle("工具输出契约校验");
        step.setArguments(StrUtil.maxLength(call.name() + "："
                + StrUtil.blankToDefault(call.arguments(), "{}"), MAX_STEP_ARGS));
        StringBuilder result = new StringBuilder();
        for (AssistantToolContractService.Violation violation : check.violations()) {
            if (!result.isEmpty()) {
                result.append('、');
            }
            result.append(violation.code());
        }
        result.append("；").append(StrUtil.blankToDefault(note, check.repairNote()));
        step.setResult(StrUtil.maxLength(result.toString(), MAX_STEP_RESULT));
        step.setSource(toolRegistry.title(call.name()));
        step.setSuccess(repaired);
        step.setTime(LocalDateTime.now());
        return step;
    }

    /**
     * 大模型在工具执行后失败：把已执行结果如实返回，不重复执行写操作
     */
    private AgentOutcome partialOutcome(List<AgentStepVO> steps, long tokens, String actionType,
                                        String actionLabel, String actionTarget, Object actionData,
                                        Exception error) {
        StringBuilder reply = new StringBuilder("模型总结暂时不可用，我先把你让我做的事的结果列出来：\n");
        for (AgentStepVO step : steps) {
            if (step.isSuccess()) {
                reply.append("- ").append(step.getTitle()).append("：")
                        .append(StrUtil.maxLength(step.getResult(), 120)).append('\n');
            }
        }
        reply.append("（").append(StrUtil.maxLength(error.getMessage(), 80)).append("）");
        return new AgentOutcome(reply.toString(), "AGENT", true, steps, actionType, actionLabel,
                actionTarget, actionData, tokens, List.of());
    }

    private String summarize(String message, List<AgentStepVO> steps, String systemPrompt) {
        StringBuilder prompt = new StringBuilder("【用户问题】").append(message).append("\n【工具执行结果】\n");
        for (AgentStepVO step : steps) {
            prompt.append("- ").append(step.getTitle()).append("：")
                    .append(StrUtil.maxLength(step.getResult(), MAX_SUMMARY_CHARS)).append('\n');
        }
        prompt.append("请基于以上结果直接回答用户，控制在 200 字以内。");
        try {
            return llmInvokeService.chat(systemPrompt, prompt.toString(), "default");
        } catch (Exception e) {
            StringBuilder reply = new StringBuilder("工具已经执行完，结果如下：\n");
            for (AgentStepVO step : steps) {
                reply.append("- ").append(step.getTitle()).append("：")
                        .append(StrUtil.maxLength(step.getResult(), 120)).append('\n');
            }
            return reply.toString();
        }
    }

    /**
     * 规则降级通道：无模型时仍能查数据、查知识库、记/取长期记忆
     */
    private AgentOutcome fallback(AssistantIntentEnum intent, String message) {
        if (isMemoryQuestion(message)) {
            AgentOutcome memoryOutcome = memoryAnswer(message);
            if (memoryOutcome != null) {
                return memoryOutcome;
            }
        }
        if (isMemoryWrite(message)) {
            return memoryWrite(message);
        }
        if (actionService.executable(intent)) {
            long start = System.currentTimeMillis();
            AssistantActionService.ActionResult result = actionService.execute(intent, message);
            long latency = System.currentTimeMillis() - start;
            AgentStepVO step = new AgentStepVO();
            step.setStepNo(1);
            step.setType("TOOL");
            step.setToolName(toolNameFor(intent));
            step.setTitle(toolRegistry.title(toolNameFor(intent)));
            step.setArguments(StrUtil.maxLength(message, MAX_STEP_ARGS));
            step.setResult(StrUtil.maxLength(result.reply(), MAX_STEP_RESULT));
            step.setSource("系统实时数据");
            step.setLatencyMs(latency);
            step.setSuccess(true);
            step.setTime(LocalDateTime.now());
            return new AgentOutcome(result.reply(), "RULE", true, List.of(step),
                    result.actionType(), result.actionLabel(), result.actionTarget(), result.data(),
                    0L, List.of());
        }
        return knowledgeFallback(intent, message);
    }

    /**
     * 用户显式要求记忆（“我叫 X”“记住 XX”），且没有模型时直接落库并确认
     */
    private AgentOutcome memoryWrite(String message) {
        List<MemoryItem> saved;
        try {
            saved = memoryService.extract(message);
        } catch (Exception e) {
            log.warn("记忆写入失败 msg={}", e.getMessage());
            return null;
        }
        if (saved.isEmpty()) {
            return null;
        }
        StringBuilder reply = new StringBuilder("好，我记住了：");
        for (MemoryItem item : saved) {
            reply.append(item.key()).append(" = ").append(item.value()).append("；");
        }
        reply.append("下次对话我会带上这些信息。");
        return new AgentOutcome(reply.toString(), "RULE", true, memorySteps(saved),
                AssistantActionType.NONE, null, null, null, 0L, saved);
    }

    /**
     * 询问“我叫什么/你记得我什么”时，从长期记忆回答
     */
    private AgentOutcome memoryAnswer(String message) {
        List<AssistantUserMemory> facts;
        try {
            facts = memoryService.list(20);
        } catch (Exception e) {
            log.warn("记忆读取失败 msg={}", e.getMessage());
            return null;
        }
        boolean nameAsk = message.contains("名字") || message.contains("称呼");
        List<AssistantUserMemory> selected = facts.stream()
                .filter(fact -> nameAsk
                        ? "称呼".equals(fact.getMemoryKey())
                        : SimilarityUtil.textSimilarity(message,
                        fact.getMemoryKey() + fact.getMemoryValue()) > 0.01D)
                .limit(nameAsk ? 1 : 5)
                .toList();
        // 泛问“你记得我什么”时按最近记忆回答，避免相似度门槛把结果全部过滤掉
        if (selected.isEmpty() && !nameAsk) {
            selected = facts.stream().limit(3).toList();
        }
        if (selected.isEmpty()) {
            if (!nameAsk || facts.isEmpty()) {
                return null;
            }
            return new AgentOutcome("你还没告诉我怎么称呼你，说一句“我叫小明”我就记住啦。",
                    "RULE", true, List.of(), AssistantActionType.NONE, null, null, null, 0L, List.of());
        }
        StringBuilder reply = new StringBuilder("我记得：");
        List<AgentStepVO> steps = new ArrayList<>();
        for (AssistantUserMemory fact : selected) {
            reply.append(fact.getMemoryKey()).append(" = ").append(fact.getMemoryValue()).append("；");
        }
        reply.append("需要改的话直接告诉我。");
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(1);
        step.setType("MEMORY");
        step.setToolName("recall_memory");
        step.setTitle(toolRegistry.title("recall_memory"));
        step.setArguments(StrUtil.maxLength(message, MAX_STEP_ARGS));
        step.setResult(StrUtil.maxLength(reply.toString(), MAX_STEP_RESULT));
        step.setSource("用户长期记忆");
        step.setSuccess(true);
        step.setTime(LocalDateTime.now());
        steps.add(step);
        return new AgentOutcome(reply.toString(), "RULE", true, steps,
                AssistantActionType.NONE, null, null, null, 0L, List.of());
    }

    private List<AgentStepVO> memorySteps(List<MemoryItem> saved) {
        List<AgentStepVO> steps = new ArrayList<>();
        int index = 1;
        for (MemoryItem item : saved) {
            AgentStepVO step = new AgentStepVO();
            step.setStepNo(index++);
            step.setType("MEMORY");
            step.setToolName("save_memory");
            step.setTitle("记住：" + item.key());
            step.setArguments(StrUtil.maxLength(item.value(), MAX_STEP_ARGS));
            step.setResult("已写入长期记忆");
            step.setSource("用户长期记忆（本轮写入）");
            step.setSuccess(true);
            step.setTime(LocalDateTime.now());
            steps.add(step);
        }
        return steps;
    }

    private boolean isMemoryWrite(String message) {
        return message.contains("记住") || message.matches(".*我(?:叫|的名字是)[^什么啥谁哪多少].*");
    }

    private boolean isMemoryQuestion(String message) {
        String compact = message.replaceAll("\\s+", "");
        return compact.contains("我叫什么") || compact.contains("我的名字") || compact.contains("你记得我")
                || compact.contains("我的偏好") || compact.contains("我的称呼");
    }

    /**
     * 知识库 + FAQ 兜底：先查知识库，再按关键词匹配内置 FAQ
     */
    private AgentOutcome knowledgeFallback(AssistantIntentEnum intent, String message) {
        int topK = Math.max(1, properties.getAgent().getKnowledgeTopK());
        List<RagChunkVO> hits;
        try {
            hits = knowledgeBaseService.search(message, topK);
        } catch (Exception e) {
            log.warn("知识库检索失败 msg={}", e.getMessage());
            hits = List.of();
        }
        List<AgentStepVO> steps = new ArrayList<>();
        if (!hits.isEmpty()) {
            AgentStepVO step = new AgentStepVO();
            step.setStepNo(1);
            step.setType("KNOWLEDGE");
            step.setToolName("search_knowledge");
            step.setTitle(toolRegistry.title("search_knowledge"));
            step.setArguments(StrUtil.maxLength(message, MAX_STEP_ARGS));
            step.setResult(StrUtil.maxLength(hits.get(0).getContent(), MAX_STEP_RESULT));
            step.setSource(StrUtil.blankToDefault(hits.get(0).getSource(), "平台知识库"));
            step.setLatencyMs(0L);
            step.setSuccess(true);
            step.setTime(LocalDateTime.now());
            steps.add(step);
        }

        String faq = faqAnswer(message);
        String reply;
        if (StrUtil.isNotBlank(faq)) {
            reply = faq;
            if (!hits.isEmpty() && hits.get(0).getScore() > 0.1D) {
                reply = reply + "\n来源：《" + hits.get(0).getSource() + "》";
            }
        } else if (!hits.isEmpty() && hits.get(0).getScore() > 0.02D) {
            reply = "根据平台知识库《" + hits.get(0).getSource() + "》："
                    + StrUtil.maxLength(hits.get(0).getContent(), 220);
        } else {
            reply = AssistantPromptConstant.FALLBACK_REPLY;
        }
        return new AgentOutcome(reply, "FAQ", true, steps,
                AssistantActionType.NONE, null, null, null, 0L, List.of());
    }

    /**
     * 规则抽取长期记忆，模型已调用 save_memory 时不重复写入
     */
    private AgentOutcome withMemory(String message, AgentOutcome outcome) {
        boolean savedByAgent = outcome.steps().stream()
                .anyMatch(step -> "save_memory".equals(step.getToolName()));
        if (savedByAgent) {
            return outcome;
        }
        List<MemoryItem> ruleSaved = new ArrayList<>();
        // 长期记忆会持久化，抽取前先脱敏，避免手机号/邮箱等原始 PII 落库
        String safeMessage = maskText(message);
        try {
            ruleSaved.addAll(memoryService.extract(safeMessage));
        } catch (Exception e) {
            log.warn("记忆抽取失败 msg={}", e.getMessage());
        }
        // 规则没命中时再做一次反思，覆盖“以后都用表格、先给结论”这类隐含偏好
        List<MemoryItem> reflected = List.of();
        if (ruleSaved.isEmpty()) {
            try {
                reflected = reflectionService.reflect(safeMessage);
            } catch (Exception e) {
                log.warn("记忆反思失败 msg={}", e.getMessage());
            }
        }
        if (ruleSaved.isEmpty() && reflected.isEmpty()) {
            return outcome;
        }
        List<AgentStepVO> steps = new ArrayList<>(outcome.steps());
        appendMemorySteps(steps, ruleSaved, "已写入长期记忆", "用户长期记忆（本轮写入）");
        appendMemorySteps(steps, reflected, "由对话归纳的稳定偏好，已写入长期记忆", "用户长期记忆（反思写入）");
        List<MemoryItem> saved = new ArrayList<>(ruleSaved);
        saved.addAll(reflected);
        return new AgentOutcome(outcome.reply(), outcome.mode(), outcome.degraded(), steps,
                outcome.actionType(), outcome.actionLabel(), outcome.actionTarget(), outcome.actionData(),
                outcome.tokenUsed(), saved);
    }

    /**
     * 把记忆写入结果追加成 MEMORY 轨迹，规则抽取与反思写入用来源区分
     */
    private void appendMemorySteps(List<AgentStepVO> steps, List<MemoryItem> items,
                                   String result, String source) {
        for (MemoryItem item : items) {
            AgentStepVO step = new AgentStepVO();
            step.setStepNo(steps.size() + 1);
            step.setType("MEMORY");
            step.setToolName("save_memory");
            step.setTitle("记住：" + item.key());
            step.setArguments(StrUtil.maxLength(item.value(), MAX_STEP_ARGS));
            step.setResult(result);
            step.setSource(source);
            step.setLatencyMs(0L);
            step.setSuccess(true);
            step.setTime(LocalDateTime.now());
            steps.add(step);
        }
    }

    /**
     * 上下文压缩步骤：让前端与审计能看到“这轮为什么压缩、压掉了多少”
     */
    private AgentStepVO contextStep(AssistantContextService.ContextResult context,
                                    Prd2ApiProperties.Agent config) {
        StringBuilder detail = new StringBuilder();
        if (context.summarizedMessages() > 0) {
            detail.append("早期 ").append(context.summarizedMessages()).append(" 条消息已摘要压缩");
        }
        if (context.droppedMessages() > 0) {
            if (detail.length() > 0) {
                detail.append("，");
            }
            detail.append("截断 ").append(context.droppedMessages()).append(" 条消息");
        }
        detail.append("；压缩后约 ").append(context.finalTokens()).append(" tokens");
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(1);
        step.setType("CONTEXT");
        step.setToolName("context_budget");
        step.setTitle("上下文压缩");
        step.setArguments(StrUtil.maxLength("预算 " + config.getContextTokenBudget()
                + " tokens，原始约 " + context.rawTokens() + " tokens", MAX_STEP_ARGS));
        step.setResult(StrUtil.maxLength(detail.toString(), MAX_STEP_RESULT));
        step.setSuccess(true);
        step.setTime(LocalDateTime.now());
        return step;
    }

    /**
     * 回答接地自检 + Actor-Critic 评审：先做确定性规则校验，再让模型评委打分，低分重写一次
     */
    private CheckResult applySelfCheck(String question, String reply, List<AgentStepVO> steps,
                                       AgentListener listener, AtomicInteger emitted) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isSelfCheck() || StrUtil.isBlank(reply)) {
            return new CheckResult(reply, 0L);
        }
        AssistantSelfCheckService.Result result = selfCheckService.check(question, reply, steps);
        String finalReply = reply;
        if (!result.skipped() && !result.grounded() && config.isSelfCheckRetry()) {
            String repaired = selfCheckService.repair(question, reply, steps, result.issues());
            if (StrUtil.isNotBlank(repaired)) {
                finalReply = repaired;
                result = selfCheckService.check(question, finalReply, steps);
            }
        }
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(steps.size() + 1);
        step.setType("SELF_CHECK");
        step.setToolName("answer_self_check");
        step.setTitle("回答接地自检");
        step.setArguments("核对数值出处、知识来源与写操作声明");
        if (result.skipped()) {
            step.setResult("本轮无工具/知识证据，跳过自检");
        } else if (result.grounded()) {
            step.setResult("通过：答复内容可与工具结果核对");
        } else {
            step.setResult(StrUtil.maxLength("未通过：" + String.join("；", result.issues()), MAX_STEP_RESULT));
        }
        step.setSuccess(result.grounded());
        step.setTime(LocalDateTime.now());
        steps.add(step);
        emitStep(listener, emitted, step);
        metricUtil.agentSelfCheck(result.grounded());
        return applyCritic(question, finalReply, steps, config, listener, emitted);
    }

    /**
     * Actor-Critic：评审器给出忠实/完整/可执行三维评分，未达标时把评审意见回灌重写一次
     * <p>
     * 评审是“输出侧质量门禁”：即使模型嘴上说“已基于证据”，也会被独立评委判卷并触发修订。
     */
    private CheckResult applyCritic(String question, String reply, List<AgentStepVO> steps,
                                    Prd2ApiProperties.Agent config,
                                    AgentListener listener, AtomicInteger emitted) {
        if (!config.isCriticEnabled()) {
            return new CheckResult(reply, 0L);
        }
        AssistantCriticService.Review review = criticService.review(question, reply, steps);
        if (!review.ran()) {
            return new CheckResult(reply, 0L);
        }
        String finalReply = reply;
        long tokens = 0L;
        boolean rewritten = false;
        if (!review.passed() && config.isCriticRewrite()) {
            AssistantCriticService.Rewrite rewrite =
                    criticService.rewrite(question, reply, steps, review);
            if (rewrite != null && StrUtil.isNotBlank(rewrite.reply())) {
                finalReply = rewrite.reply();
                tokens += rewrite.tokens();
                rewritten = true;
            }
        }
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(steps.size() + 1);
        step.setType("CRITIC");
        step.setToolName("answer_critic");
        step.setTitle("答复评审反思");
        step.setArguments("忠实/完整/可执行三维评分（1~5）");
        String scoreText = "忠实 " + review.faithfulness() + "，完整 " + review.completeness()
                + "，可执行 " + review.actionability() + "（总分 " + review.total() + "）";
        if (review.passed()) {
            step.setResult(scoreText + "；通过评审");
        } else {
            StringBuilder detail = new StringBuilder(scoreText + "；未通过：")
                    .append(String.join("；", review.issues()));
            detail.append(rewritten ? "；已按评审意见重写一次" : "；保留原答复");
            step.setResult(StrUtil.maxLength(detail.toString(), MAX_STEP_RESULT));
        }
        step.setSuccess(review.passed() || rewritten);
        step.setTime(LocalDateTime.now());
        steps.add(step);
        emitStep(listener, emitted, step);
        metricUtil.agentCritic(review.passed() || rewritten, review.total());
        return new CheckResult(finalReply, tokens);
    }

    /**
     * 断点续跑轨迹：让用户看到“这轮是从哪一步接着跑的”
     */
    private AgentStepVO resumeStep(AssistantCheckpointService.Snapshot snapshot) {
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(1);
        step.setType("RESUME");
        step.setToolName("resume_checkpoint");
        step.setTitle("断点续跑");
        step.setArguments(StrUtil.maxLength("上次暂停原因：" + snapshot.reason(), MAX_STEP_ARGS));
        step.setResult(StrUtil.maxLength("目标：" + StrUtil.blankToDefault(snapshot.goal(), "未记录")
                + "；已完成 " + snapshot.done().size() + " 项，本轮复用结果、不重复执行", MAX_STEP_RESULT));
        step.setSuccess(true);
        step.setTime(LocalDateTime.now());
        return step;
    }

    /**
     * 断点上下文：明确告诉模型哪些步骤已经做完，避免从头再来
     */
    private String resumeBlock(AssistantCheckpointService.Snapshot snapshot) {
        StringBuilder block = new StringBuilder(
                "\n【断点续跑】这是用户要求的“继续上一次未完成的任务”，请从剩余部分接着做。\n");
        block.append("上次目标：").append(StrUtil.blankToDefault(snapshot.goal(), "未记录")).append('\n');
        block.append("上次暂停原因：").append(StrUtil.blankToDefault(snapshot.reason(), "未记录")).append('\n');
        if (!snapshot.done().isEmpty()) {
            block.append("已完成步骤（结果可直接引用，不要重复调用相同工具与参数）：\n");
            for (AssistantCheckpointService.DoneStep done : snapshot.done()) {
                block.append("- ").append(done.toolName()).append("：")
                        .append(StrUtil.maxLength(done.summary(), 160)).append('\n');
            }
        }
        block.append("如果剩余步骤需要的参数仍然缺失，先向用户追问，不要编造。\n");
        return block.toString();
    }

    /**
     * 复用已完成的工具结果：轨迹上标为 RESUME，区别于本轮真实执行
     */
    private AgentStepVO replayStep(ToolCall call, AssistantCheckpointService.DoneStep done) {
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(1);
        step.setType("RESUME");
        step.setToolName(call.name());
        step.setTitle("断点续跑·复用结果：" + toolRegistry.title(call.name()));
        step.setArguments(StrUtil.maxLength(call.arguments(), MAX_STEP_ARGS));
        step.setResult(StrUtil.maxLength(done.summary(), MAX_STEP_RESULT));
        step.setSource(StrUtil.blankToDefault(done.source(), "断点续跑"));
        step.setLatencyMs(0L);
        step.setSuccess(true);
        step.setTime(LocalDateTime.now());
        return step;
    }

    /**
     * 经验策略轨迹：展示这类问法历史上实际用过哪些工具
     */
    private AgentStepVO policyStep(AssistantToolSelector.Selection selection) {
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(1);
        step.setType("POLICY");
        step.setToolName("tool_policy");
        step.setTitle("工具选择经验策略");
        step.setArguments("命中历史相似问法的成功工具组合");
        step.setResult(StrUtil.maxLength("推荐工具：" + String.join("、", selection.policyTools())
                + "；注入工具共 " + selection.toolNames().size() + " 个", MAX_STEP_RESULT));
        step.setSuccess(true);
        step.setTime(LocalDateTime.now());
        return step;
    }

    /**
     * 检查点：未完成的轮次存下来，已完成的轮次清理，保证“继续”只对真正没做完的任务生效
     */
    private void persistCheckpoint(String sessionId, String message,
                                   AssistantCheckpointService.Snapshot previous,
                                   AgentOutcome outcome, Prd2ApiProperties.Agent config) {
        if (config == null || !config.isCheckpointEnabled()) {
            return;
        }
        String reason = pauseReason(outcome.steps());
        if (reason == null) {
            checkpointService.clear(sessionId);
            metricUtil.agentCheckpoint("cleared", 0);
            return;
        }
        String goal = previous != null && StrUtil.isNotBlank(previous.goal()) ? previous.goal() : message;
        List<AssistantCheckpointService.DoneStep> done = checkpointService.merge(
                previous == null ? List.of() : previous.done(), outcome.steps());
        checkpointService.save(sessionId, goal, done, outcome.steps().size(), reason);
        metricUtil.agentCheckpoint("saved", done.size());
    }

    /**
     * 暂停原因：只有真正“没做完”的轮次才值得续跑
     */
    private String pauseReason(List<AgentStepVO> steps) {
        for (AgentStepVO step : steps) {
            String type = StrUtil.blankToDefault(step.getType(), "");
            if ("CANCEL".equals(type)) {
                return "用户中断执行";
            }
            if ("BUDGET".equals(type)) {
                return "预算或时限熔断";
            }
            if ("CONFIRM".equals(type)) {
                return "等待人工确认";
            }
            if ("CLARIFY".equals(type)) {
                return "等待补充参数";
            }
        }
        boolean failedEvidence = steps.stream()
                .anyMatch(step -> !step.isSuccess() && isEvidenceStep(step.getType()));
        return failedEvidence ? "有步骤执行失败" : null;
    }

    /**
     * 经验学习：把本轮成功的工具组合记到“问法 → 工具”的长期先验里
     */
    private void learnToolPolicy(String message, AgentOutcome outcome) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isToolPolicyEnabled()) {
            return;
        }
        List<String> tools = outcome.steps().stream()
                .filter(step -> "TOOL".equals(step.getType()) && step.isSuccess())
                .map(AgentStepVO::getToolName)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        if (tools.isEmpty()) {
            return;
        }
        try {
            toolPolicyService.learn(message, tools);
        } catch (Exception e) {
            log.warn("工具经验策略学习失败 msg={}", e.getMessage());
        }
    }

    /**
     * 目标漂移轨迹：展示聚焦比例与偏离步骤，便于复盘“为什么拐弯”
     */
    private AgentStepVO driftStep(AssistantGoalDriftService.Assessment drift) {
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(1);
        step.setType("DRIFT");
        step.setToolName("goal_drift");
        step.setTitle("目标漂移自检");
        step.setArguments(StrUtil.maxLength("聚焦比例 " + drift.focusRatio()
                + "，命中 " + drift.onTopicSteps() + "/" + drift.steps() + " 步", MAX_STEP_ARGS));
        String detail = drift.suggestion();
        if (!drift.offTopic().isEmpty()) {
            detail += "；偏离步骤：" + String.join("、", drift.offTopic());
        }
        step.setResult(StrUtil.maxLength(detail, MAX_STEP_RESULT));
        step.setSuccess(false);
        step.setTime(LocalDateTime.now());
        return step;
    }

    /**
     * 路由模型调用失败时回落默认模型：档位配置写错或该模型额度耗尽，也不让整轮对话直接失败
     */
    private ToolChatResult invokeWithFallback(List<Map<String, Object>> messages, String toolsJson,
                                             String modelCode) {
        try {
            return llmInvokeService.chatWithTools(messages, toolsJson, modelCode);
        } catch (Exception e) {
            if ("default".equals(modelCode)) {
                throw e;
            }
            log.warn("路由模型调用失败，回落默认模型 model={} msg={}", modelCode, e.getMessage());
            return llmInvokeService.chatWithTools(messages, toolsJson, "default");
        }
    }

    /**
     * 模型路由轨迹：展示本轮问题复杂度打分与所选模型档位
     */
    private AgentStepVO modelRouteStep(AssistantModelRoutingService.Route route) {
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(1);
        step.setType("MODEL");
        step.setToolName("model_route");
        step.setTitle("模型复杂度路由");
        step.setArguments(StrUtil.maxLength("复杂度得分 " + route.score() + "，档位 " + route.level(),
                MAX_STEP_ARGS));
        String detail = "选用模型 " + route.modelCode();
        if (!route.factors().isEmpty()) {
            detail += "；" + String.join("；", route.factors());
        }
        step.setResult(StrUtil.maxLength(detail, MAX_STEP_RESULT));
        step.setSuccess(true);
        step.setTime(LocalDateTime.now());
        return step;
    }

    /**
     * 工具动态检索轨迹：让用户看到“这轮为什么只给了模型这几个工具”
     */
    private AgentStepVO routeStep(AssistantToolSelector.Selection selection) {
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(1);
        step.setType("ROUTE");
        step.setToolName("tool_route");
        step.setTitle("工具动态检索");
        step.setArguments(StrUtil.maxLength("候选 " + selection.candidates() + " 个工具，按语义匹配裁剪",
                MAX_STEP_ARGS));
        step.setResult(StrUtil.maxLength("注入 " + selection.toolNames().size() + " 个工具："
                + String.join("、", selection.toolNames()), MAX_STEP_RESULT));
        step.setSuccess(true);
        step.setTime(LocalDateTime.now());
        return step;
    }

    /**
     * 重复调用拦截轨迹
     */
    private AgentStepVO loopStep(ToolCall call, int repeat) {
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(1);
        step.setType("LOOP");
        step.setToolName(call.name());
        step.setTitle("重复调用拦截");
        step.setArguments(StrUtil.maxLength(call.arguments(), MAX_STEP_ARGS));
        step.setResult(StrUtil.maxLength("相同工具与参数第 " + repeat + " 次调用，已阻止继续执行以避免死循环",
                MAX_STEP_RESULT));
        step.setSuccess(false);
        step.setTime(LocalDateTime.now());
        return step;
    }

    /**
     * 必填参数缺失：不盲目执行，先向用户追问，避免拿错误参数去查/改数据
     */
    private AgentOutcome clarifyOutcome(List<AgentStepVO> steps, long tokens, ToolCall call,
                                        List<String> missing, AgentListener listener, AtomicInteger emitted) {
        String title = toolRegistry.title(call.name());
        List<String> labels = missing.stream().map(this::fieldLabel).toList();
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(steps.size() + 1);
        step.setType("CLARIFY");
        step.setToolName(call.name());
        step.setTitle("待补充参数：" + title);
        step.setArguments(StrUtil.maxLength(call.arguments(), MAX_STEP_ARGS));
        step.setResult(StrUtil.maxLength("缺少必填参数：" + String.join("、", labels), MAX_STEP_RESULT));
        step.setSuccess(false);
        step.setTime(LocalDateTime.now());
        steps.add(step);
        emitStep(listener, emitted, step);
        String hint = missing.contains("taskId")
                ? "也可以先让我列出最近的任务，你从中挑一个。" : "补充后我立刻继续。";
        String reply = "「" + title + "」还差 " + String.join("、", labels) + " 才能执行。" + hint;
        return new AgentOutcome(reply, "AGENT", false, steps, AssistantActionType.NONE, null,
                null, null, tokens, List.of());
    }

    private String fieldLabel(String field) {
        return switch (StrUtil.blankToDefault(field, "")) {
            case "taskId" -> "任务 ID（32 位十六进制）";
            case "query" -> "检索关键词";
            case "key" -> "记忆键";
            case "value" -> "记忆内容";
            case "page" -> "页面名称";
            case "expert" -> "专家类型";
            case "goal" -> "子目标描述";
            default -> StrUtil.blankToDefault(field, "参数");
        };
    }

    /**
     * 预算/死循环熔断：停止自动执行，用已有结果收尾，保证用户始终拿到可用答复
     */
    private AgentOutcome budgetOutcome(List<AgentStepVO> steps, long tokens, String actionType,
                                       String actionLabel, String actionTarget, Object actionData,
                                       String reason, AgentListener listener, AtomicInteger emitted) {
        String label = switch (reason) {
            case "token" -> "本轮 token 预算已用完";
            case "deadline" -> "本轮执行超过时限";
            default -> "检测到重复调用，已中止以免死循环";
        };
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(steps.size() + 1);
        step.setType("BUDGET");
        step.setToolName("agent_budget");
        step.setTitle("执行保护触发");
        step.setArguments(StrUtil.maxLength("原因：" + label, MAX_STEP_ARGS));
        step.setResult(StrUtil.maxLength(label + "，停止继续调度工具，改用已有结果收尾", MAX_STEP_RESULT));
        step.setSuccess(false);
        step.setTime(LocalDateTime.now());
        steps.add(step);
        emitStep(listener, emitted, step);
        metricUtil.agentBudgetStop(reason);
        StringBuilder reply = new StringBuilder();
        List<AgentStepVO> done = steps.stream().filter(AgentStepVO::isSuccess).toList();
        if (done.isEmpty()) {
            reply.append(label).append("，这轮没能拿到结果。可以把问题拆小一点再问一次。");
        } else {
            reply.append(label).append("，我先把已经拿到的结果给你：\n");
            for (AgentStepVO item : done) {
                reply.append("- ").append(item.getTitle()).append("：")
                        .append(StrUtil.maxLength(item.getResult(), 120)).append('\n');
            }
        }
        return new AgentOutcome(reply.toString(), "AGENT", false, steps, actionType, actionLabel,
                actionTarget, actionData, tokens, List.of());
    }

    /**
     * 用户中断：停止调度，把已执行结果如实返回
     */
    private AgentOutcome cancelOutcome(List<AgentStepVO> steps, long tokens, String actionType,
                                       String actionLabel, String actionTarget, Object actionData,
                                       AgentListener listener, AtomicInteger emitted) {
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(steps.size() + 1);
        step.setType("CANCEL");
        step.setToolName("agent_cancel");
        step.setTitle("用户中断执行");
        step.setArguments("收到中断请求，停止后续模型决策与工具调用");
        step.setResult("已在安全检查点停止，不再发起新的工具调用；已完成步骤的结果保持有效");
        step.setSuccess(false);
        step.setTime(LocalDateTime.now());
        steps.add(step);
        emitStep(listener, emitted, step);
        metricUtil.agentCancel();

        List<AgentStepVO> done = steps.stream()
                .filter(item -> item.isSuccess() && isEvidenceStep(item.getType()))
                .toList();
        StringBuilder reply = new StringBuilder("已按你的请求中断本轮执行。");
        if (done.isEmpty()) {
            reply.append("这轮还没拿到结果，可以换个更具体的问题让我重试。");
        } else {
            reply.append("已经拿到的结果先给你：\n");
            for (AgentStepVO item : done) {
                reply.append("- ").append(item.getTitle()).append("：")
                        .append(StrUtil.maxLength(item.getResult(), 120)).append('\n');
            }
        }
        return new AgentOutcome(reply.toString(), "AGENT", false, steps, actionType, actionLabel,
                actionTarget, actionData, tokens, List.of());
    }

    /**
     * 证据类步骤：可以作为答复依据的轨迹类型
     */
    private boolean isEvidenceStep(String type) {
        return "TOOL".equals(type) || "KNOWLEDGE".equals(type)
                || "SUBAGENT".equals(type) || "MEMORY".equals(type);
    }

    /**
     * 计划轨迹：让用户看到“这轮打算怎么做”，并作为执行依据注入提示词
     */
    private AgentStepVO planStep(String type, AssistantPlannerService.Plan plan, String trigger) {
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(1);
        step.setType(type);
        step.setToolName("task_plan");
        step.setTitle("REPLAN".equals(type) ? "计划重规划" : "任务执行计划");
        step.setArguments(StrUtil.maxLength(trigger == null
                ? "复合任务拆解为有序步骤" : "触发原因：" + trigger, MAX_STEP_ARGS));
        StringBuilder detail = new StringBuilder();
        if (StrUtil.isNotBlank(plan.goal())) {
            detail.append("目标：").append(plan.goal()).append("；");
        }
        detail.append("步骤：");
        for (AssistantPlannerService.PlanStep item : plan.steps()) {
            detail.append(item.no()).append('.').append(item.action());
            if (StrUtil.isNotBlank(item.reason())) {
                detail.append('(').append(item.reason()).append(')');
            }
            detail.append(' ');
        }
        step.setResult(StrUtil.maxLength(detail.toString().trim(), MAX_STEP_RESULT));
        step.setSuccess(true);
        step.setTime(LocalDateTime.now());
        return step;
    }

    /**
     * 计划文本注入系统提示词：明确顺序与当前步，减少模型漫游式试错
     */
    private String planBlock(AssistantPlannerService.Plan plan) {
        StringBuilder block = new StringBuilder(
                "\n【执行计划】（这是你自己刚拆解的计划，请按顺序推进；某步失败可自行调整，不要编造结果）\n");
        if (StrUtil.isNotBlank(plan.goal())) {
            block.append("目标：").append(plan.goal()).append('\n');
        }
        for (AssistantPlannerService.PlanStep item : plan.steps()) {
            block.append(item.no()).append(". ").append(item.action());
            if (StrUtil.isNotBlank(item.reason())) {
                block.append("（").append(item.reason()).append("）");
            }
            block.append('\n');
        }
        return block.toString();
    }

    /**
     * 失败摘要：只取失败步骤的标题与结果，供重规划器判断“哪一步走不通”
     */
    private String failureSummary(List<AgentStepVO> steps) {
        StringBuilder summary = new StringBuilder();
        for (AgentStepVO step : steps) {
            if (step.isSuccess() || !isEvidenceStep(step.getType())) {
                continue;
            }
            summary.append("- ").append(step.getTitle()).append("：")
                    .append(StrUtil.maxLength(step.getResult(), 120)).append('\n');
            if (summary.length() > 240) {
                break;
            }
        }
        return StrUtil.isBlank(summary.toString()) ? "工具执行失败" : summary.toString();
    }

    /**
     * 子 Agent 轨迹：Supervisor 返回的结构化结论转成前端可展示的一步
     */
    private AgentStepVO subAgentStep(AssistantSupervisorService.ExpertResult expert) {
        AgentStepVO step = new AgentStepVO();
        step.setType("SUBAGENT");
        step.setToolName("delegate_task");
        step.setTitle(expert.success() ? "委派" + expert.label() : "委派" + expert.label() + "失败");
        step.setArguments(StrUtil.maxLength("专家：" + expert.expert(), MAX_STEP_ARGS));
        step.setResult(StrUtil.maxLength(expert.summary(), MAX_STEP_RESULT));
        step.setSource(expert.source());
        step.setLatencyMs(expert.latencyMs());
        step.setSuccess(expert.success());
        step.setTime(LocalDateTime.now());
        return step;
    }

    /**
     * Supervisor fan-out/fan-in 轨迹：展示并行收益与缺失/冲突，便于解释“为什么比串行快”
     */
    private AgentStepVO supervisorStep(AssistantSupervisorService.Result fanOut) {
        long saved = Math.max(0L, fanOut.serialMs() - fanOut.wallMs());
        long ratio = fanOut.serialMs() <= 0 ? 0L : Math.round(saved * 100.0D / fanOut.serialMs());
        AgentStepVO step = new AgentStepVO();
        step.setType("SUPERVISOR");
        step.setToolName("supervisor_fanout");
        step.setTitle("Supervisor 并行汇总");
        step.setArguments(StrUtil.maxLength("并行委派 " + fanOut.experts().size()
                + " 个专家，单专家超时与 token 预算独立", MAX_STEP_ARGS));
        StringBuilder result = new StringBuilder();
        result.append("并行耗时 ").append(fanOut.wallMs()).append("ms，串行预计 ")
                .append(fanOut.serialMs()).append("ms，节省 ").append(saved)
                .append("ms（").append(ratio).append("%）；成功 ")
                .append(fanOut.experts().stream()
                        .filter(AssistantSupervisorService.ExpertResult::success).count())
                .append('/').append(fanOut.experts().size());
        if (!fanOut.missing().isEmpty()) {
            result.append("；缺失：").append(String.join("；", fanOut.missing()));
        }
        if (!fanOut.conflicts().isEmpty()) {
            result.append("；疑似冲突：").append(String.join("；", fanOut.conflicts()));
        }
        step.setResult(StrUtil.maxLength(result.toString(), MAX_STEP_RESULT));
        step.setSuccess(fanOut.missing().isEmpty());
        step.setTime(LocalDateTime.now());
        return step;
    }

    private AgentStepVO buildStep(int stepNo, ToolCall call,
                                  AssistantToolRegistry.ToolResult result, long latency) {
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(stepNo);
        step.setType(stepType(call.name()));
        step.setToolName(call.name());
        step.setTitle(toolRegistry.title(call.name()));
        step.setArguments(StrUtil.maxLength(call.arguments(), MAX_STEP_ARGS));
        step.setResult(StrUtil.maxLength(result.summary(), MAX_STEP_RESULT));
        step.setSource(sourceOf(call.name(), result));
        step.setLatencyMs(latency);
        step.setSuccess(result.success());
        step.setTime(LocalDateTime.now());
        return step;
    }

    /**
     * 证据来源：知识库取命中文档名，记忆类标注长期记忆，其余为系统实时数据
     */
    private String sourceOf(String toolName, AssistantToolRegistry.ToolResult result) {
        String name = StrUtil.blankToDefault(toolName, "");
        if ("search_knowledge".equals(name)) {
            Object data = result == null ? null : result.data();
            if (data instanceof Map<?, ?> map && map.get("hits") instanceof List<?> hits && !hits.isEmpty()
                    && hits.get(0) instanceof RagChunkVO chunk) {
                return StrUtil.blankToDefault(chunk.getSource(), "平台知识库");
            }
            return "平台知识库";
        }
        if ("recall_memory".equals(name)) {
            return "用户长期记忆";
        }
        if ("save_memory".equals(name)) {
            return "用户长期记忆（本轮写入）";
        }
        return "系统实时数据";
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

    private String memoryBlock(List<MemoryItem> facts) {
        if (facts == null || facts.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder("\n【用户长期记忆】（用于个性化，不要复述无关记忆）\n");
        for (MemoryItem fact : facts) {
            block.append("- ").append(fact.key()).append("：").append(fact.value()).append('\n');
        }
        return block.toString();
    }

    private String stepType(String toolName) {
        return switch (StrUtil.blankToDefault(toolName, "")) {
            case "search_knowledge" -> "KNOWLEDGE";
            case "save_memory", "recall_memory" -> "MEMORY";
            default -> "TOOL";
        };
    }

    private String toolNameFor(AssistantIntentEnum intent) {
        return switch (intent) {
            case TASK_LIST -> "query_my_tasks";
            case TASK_STATUS -> "get_task_status";
            case CANCEL_TASK -> "cancel_task";
            case RETRY_TASK -> "retry_task";
            case QUOTA -> "query_my_quota";
            case VIP -> "query_my_vip";
            case FILE_LIST -> "list_my_files";
            case ADMIN_STATS -> "query_admin_stats";
            case GUIDE_UPLOAD, GUIDE_EXPORT -> "navigate_page";
            default -> "search_knowledge";
        };
    }

    /**
     * 内置 FAQ 关键词匹配，未命中返回 null
     */
    private String faqAnswer(String message) {
        String lower = message.toLowerCase();
        for (AssistantPromptConstant.Faq faq : AssistantPromptConstant.FAQ) {
            for (String keyword : faq.getKeywords()) {
                if (StrUtil.isNotBlank(keyword) && lower.contains(keyword.trim().toLowerCase())) {
                    return faq.getAnswer();
                }
            }
        }
        return null;
    }
}
