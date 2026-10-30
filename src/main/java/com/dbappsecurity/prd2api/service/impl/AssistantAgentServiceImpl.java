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
import com.dbappsecurity.prd2api.service.AssistantConfirmationService;
import com.dbappsecurity.prd2api.service.AssistantContextService;
import com.dbappsecurity.prd2api.service.AssistantMemoryService;
import com.dbappsecurity.prd2api.service.AssistantMemoryService.MemoryItem;
import com.dbappsecurity.prd2api.service.AssistantSelfCheckService;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import com.dbappsecurity.prd2api.service.LlmInvokeService.ToolCall;
import com.dbappsecurity.prd2api.service.LlmInvokeService.ToolChatResult;
import com.dbappsecurity.prd2api.util.AssistantGuardUtil;
import com.dbappsecurity.prd2api.util.AssistantIntentResolver;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.SimilarityUtil;
import com.dbappsecurity.prd2api.vo.AgentStepVO;
import com.dbappsecurity.prd2api.vo.RagChunkVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                outcome = react(sessionId, message, history, config, forceConfirm, listener, emitted);
            } catch (Exception e) {
                log.warn("Agent 循环失败，降级规则通道 msg={}", e.getMessage());
                outcome = fallback(intent, message);
            }
        }
        if (guardNote != null) {
            outcome = withGuardStep(outcome, guardNote);
        }
        outcome = withMemory(message, outcome);
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
        List<RagChunkVO> hits = knowledgeBaseService.search(message,
                Math.max(1, config.getKnowledgeTopK()));
        StringBuilder prompt = new StringBuilder();
        if (!hits.isEmpty()) {
            prompt.append("【知识库检索结果】\n");
            for (RagChunkVO hit : hits) {
                prompt.append("- 《").append(hit.getSource()).append("》")
                        .append(StrUtil.maxLength(hit.getContent(), 500)).append('\n');
            }
            AgentStepVO step = new AgentStepVO();
            step.setStepNo(1);
            step.setType("KNOWLEDGE");
            step.setToolName("search_knowledge");
            step.setTitle(toolRegistry.title("search_knowledge"));
            step.setArguments(StrUtil.maxLength(message, MAX_STEP_ARGS));
            step.setResult(StrUtil.maxLength(hits.get(0).getContent(), MAX_STEP_RESULT));
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
        reply = applySelfCheck(message, reply, steps, null, null);
        return new AgentOutcome(reply, "LLM", false, steps,
                AssistantActionType.NONE, null, null, null, 0L, List.of());
    }

    /**
     * ReAct 主循环：模型决策 → 工具执行 → 结果回填 → 再决策，直到给出最终答复
     */
    private AgentOutcome react(String sessionId, String message, List<AssistantChatMessage> history,
                               Prd2ApiProperties.Agent config, boolean forceConfirm,
                               AgentListener listener, AtomicInteger emitted) {
        List<AgentStepVO> steps = new ArrayList<>();
        List<MemoryItem> facts = memoryService.recall(message, config.getMemoryInjectLimit());
        String systemPrompt = AssistantPromptConstant.SYSTEM_PROMPT
                + AssistantPromptConstant.TOOL_PROMPT + memoryBlock(facts);
        // 上下文工程：超预算时对早期对话做摘要压缩，而不是静默丢弃
        AssistantContextService.ContextResult context =
                contextService.build(sessionId, systemPrompt, history, message);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", context.systemPrompt()));
        messages.addAll(context.messages());
        if (context.compressed()) {
            AgentStepVO contextStep = contextStep(context, config);
            steps.add(contextStep);
            emitStep(listener, emitted, contextStep);
        }

        String toolsJson = toolRegistry.definitionsJson();
        long tokens = 0L;
        String actionType = AssistantActionType.NONE;
        String actionLabel = null;
        String actionTarget = null;
        Object actionData = null;

        for (int stepNo = 1; stepNo <= config.getMaxSteps(); stepNo++) {
            if (listener != null) {
                listener.onPhase(stepNo, "THINKING");
            }
            ToolChatResult result;
            try {
                result = llmInvokeService.chatWithTools(messages, toolsJson, "default");
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
                reply = applySelfCheck(message, reply, steps, listener, emitted);
                return new AgentOutcome(reply, "AGENT", false, steps, actionType, actionLabel,
                        actionTarget, actionData, tokens, List.of());
            }
            messages.add(assistantToolCallMessage(result));

            // 先分流：危险操作生成确认票据，其余工具并行执行
            List<ToolCall> executable = new ArrayList<>();
            PendingAction pending = null;
            for (ToolCall call : result.toolCalls()) {
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

            for (AssistantToolExecutor.Execution execution : toolExecutor.executeAll(executable)) {
                ToolCall call = execution.call();
                AssistantToolRegistry.ToolResult toolResult = execution.result();
                AgentStepVO step = buildStep(stepNo, call, toolResult, execution.latencyMs());
                step.setStepNo(steps.size() + 1);
                steps.add(step);
                emitStep(listener, emitted, step);
                messages.add(Map.of("role", "tool",
                        "tool_call_id", StrUtil.blankToDefault(call.id(), "call_" + stepNo),
                        "content", StrUtil.blankToDefault(toolResult.summary(), "工具已执行，无文本结果")));
                if (toolResult.success() && StrUtil.isNotBlank(toolResult.actionType())
                        && !AssistantActionType.NONE.equals(toolResult.actionType())) {
                    actionType = toolResult.actionType();
                    actionLabel = toolResult.actionLabel();
                    actionTarget = toolResult.actionTarget();
                    actionData = toolResult.data();
                }
            }

            // 有危险操作待确认：立刻返回确定性提示，避免模型误以为已执行
            if (pending != null) {
                return pendingOutcome(steps, tokens, pending);
            }
        }
        // 达到步数上限：再做一次不带工具的总结，避免把原始 JSON 抛给用户
        String reply = summarize(message, steps, systemPrompt);
        reply = applySelfCheck(message, reply, steps, listener, emitted);
        return new AgentOutcome(reply, "AGENT", false, steps, actionType, actionLabel,
                actionTarget, actionData, tokens, List.of());
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

    private void emitStep(AgentListener listener, AtomicInteger emitted, AgentStepVO step) {
        if (listener != null) {
            emitted.incrementAndGet();
            listener.onStep(step);
        }
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
        List<MemoryItem> saved;
        try {
            saved = memoryService.extract(message);
        } catch (Exception e) {
            log.warn("记忆抽取失败 msg={}", e.getMessage());
            return outcome;
        }
        if (saved.isEmpty()) {
            return outcome;
        }
        List<AgentStepVO> steps = new ArrayList<>(outcome.steps());
        for (MemoryItem item : saved) {
            AgentStepVO step = new AgentStepVO();
            step.setStepNo(steps.size() + 1);
            step.setType("MEMORY");
            step.setToolName("save_memory");
            step.setTitle("记住：" + item.key());
            step.setArguments(StrUtil.maxLength(item.value(), MAX_STEP_ARGS));
            step.setResult("已写入长期记忆");
            step.setLatencyMs(0L);
            step.setSuccess(true);
            step.setTime(LocalDateTime.now());
            steps.add(step);
        }
        return new AgentOutcome(outcome.reply(), outcome.mode(), outcome.degraded(), steps,
                outcome.actionType(), outcome.actionLabel(), outcome.actionTarget(), outcome.actionData(),
                outcome.tokenUsed(), saved);
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
     * 回答接地自检：未通过且开启重写时，让模型严格基于工具证据重写一次，然后在轨迹留痕
     */
    private String applySelfCheck(String question, String reply, List<AgentStepVO> steps,
                                  AgentListener listener, AtomicInteger emitted) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isSelfCheck() || StrUtil.isBlank(reply)) {
            return reply;
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
        return finalReply;
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
        step.setLatencyMs(latency);
        step.setSuccess(result.success());
        step.setTime(LocalDateTime.now());
        return step;
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
