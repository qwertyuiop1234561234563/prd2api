package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolExecutor;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.entity.AssistantAgentTrace;
import com.dbappsecurity.prd2api.entity.AssistantChatSession;
import com.dbappsecurity.prd2api.mapper.AssistantAgentTraceMapper;
import com.dbappsecurity.prd2api.mapper.AssistantChatSessionMapper;
import com.dbappsecurity.prd2api.service.AssistantReplayService;
import com.dbappsecurity.prd2api.service.LlmInvokeService.ToolCall;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.SimilarityUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 轨迹回放实现
 * <p>
 * 只回放只读工具（检索、查询、导航），写操作与危险动作直接跳过；
 * 用“成败一致 + 结果 bigram 相似度 ≥ 0.6”判定一致，否则记为漂移。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantReplayServiceImpl implements AssistantReplayService {

    /** 可安全回放的只读工具 */
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "search_knowledge", "recall_memory", "query_my_tasks", "get_task_status", "query_my_quota",
            "query_my_vip", "query_admin_stats", "list_my_files", "navigate_page");

    /** 结果一致阈值：bigram Dice 系数 */
    private static final double MATCH_THRESHOLD = 0.6D;

    private static final int MAX_LIMIT = 50;
    private static final int MAX_TRACE_SCAN = 200;

    private final AssistantAgentTraceMapper traceMapper;
    private final AssistantChatSessionMapper sessionMapper;
    private final AssistantToolExecutor toolExecutor;
    private final MetricUtil metricUtil;

    @Override
    public Report replay(String sessionId, int limit) {
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        AssistantChatSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AssistantChatSession>()
                .eq(AssistantChatSession::getSessionId, sessionId)
                .eq(AssistantChatSession::getTenantId, tenantId)
                .eq(AssistantChatSession::getUserId, userId));
        if (session == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "会话不存在或不属于当前用户");
        }
        // 轨迹只按会话隔离：历史数据的 userId 可能是登录名口径，不能再次按 userId 过滤
        List<AssistantAgentTrace> traces = traceMapper.selectList(
                new LambdaQueryWrapper<AssistantAgentTrace>()
                        .eq(AssistantAgentTrace::getSessionId, session.getSessionId())
                        .eq(AssistantAgentTrace::getTenantId, tenantId)
                        .orderByAsc(AssistantAgentTrace::getStepNo));
        int cap = Math.min(MAX_LIMIT, Math.max(1, limit));
        List<Item> items = new ArrayList<>();
        int skippedWrites = 0;
        for (AssistantAgentTrace trace : traces) {
            if (!isToolStep(trace)) {
                continue;
            }
            if (!READ_ONLY_TOOLS.contains(trace.getToolName())) {
                skippedWrites++;
                continue;
            }
            if (items.size() >= cap) {
                break;
            }
            items.add(replayOne(trace));
        }
        int matched = (int) items.stream().filter(item -> "matched".equals(item.verdict())).count();
        int drifted = items.size() - matched;
        log.info("轨迹回放完成 session={} total={} matched={} drifted={} skippedWrites={}",
                sessionId, items.size(), matched, drifted, skippedWrites);
        return new Report(sessionId, items.size(), matched, drifted, skippedWrites, items);
    }

    private Item replayOne(AssistantAgentTrace trace) {
        long start = System.currentTimeMillis();
        ToolCall call = new ToolCall("replay_" + trace.getStepNo(), trace.getToolName(),
                StrUtil.blankToDefault(trace.getToolArgs(), "{}"));
        AssistantToolExecutor.Execution execution = toolExecutor.executeAll(List.of(call)).get(0);
        long latency = execution.latencyMs() > 0 ? execution.latencyMs() : System.currentTimeMillis() - start;
        boolean beforeSuccess = trace.getSuccess() != null && trace.getSuccess() == 1;
        boolean afterSuccess = execution.result().success();
        String before = StrUtil.blankToDefault(trace.getToolResult(), "");
        String after = StrUtil.blankToDefault(execution.result().summary(), "");
        double similarity = SimilarityUtil.textSimilarity(before, after);
        String verdict = beforeSuccess == afterSuccess && similarity >= MATCH_THRESHOLD
                ? "matched" : "drifted";
        metricUtil.agentReplay(verdict);
        return new Item(trace.getStepNo() == null ? 0 : trace.getStepNo(), trace.getToolName(),
                call.arguments(), beforeSuccess, afterSuccess, similarity, verdict,
                StrUtil.maxLength(before, 120), StrUtil.maxLength(after, 120),
                trace.getLatencyMs() == null ? 0L : trace.getLatencyMs(), latency,
                execution.cacheHit());
    }

    private boolean isToolStep(AssistantAgentTrace trace) {
        String type = StrUtil.blankToDefault(trace.getStepType(), "");
        return ("TOOL".equals(type) || "KNOWLEDGE".equals(type))
                && StrUtil.isNotBlank(trace.getToolName());
    }
}
