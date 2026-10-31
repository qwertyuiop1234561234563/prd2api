package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.entity.AssistantAgentTrace;
import com.dbappsecurity.prd2api.entity.AssistantChatSession;
import com.dbappsecurity.prd2api.mapper.AssistantAgentTraceMapper;
import com.dbappsecurity.prd2api.mapper.AssistantChatSessionMapper;
import com.dbappsecurity.prd2api.service.AssistantEfficiencyService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 会话执行效率画像实现
 * <p>
 * 判断口径全部来自轨迹，不依赖模型：同一工具同一参数（按原始 JSON 文本比对）出现多次即为重复调用，
 * 除首次外都记作浪费；同一工具同参数的失败出现 2 次及以上记作一组无效重试。
 * 这是“成本治理”里最容易落地的一刀：先能量化空转，才能谈优化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantEfficiencyServiceImpl implements AssistantEfficiencyService {

    private static final int MAX_TRACE_SCAN = 200;
    /** 重复调用占比超过该阈值时给出优化建议 */
    private static final double HIGH_DUPLICATE_RATE = 0.2D;
    /** 失败占比超过该阈值时建议先做失败归因 */
    private static final double HIGH_FAILURE_RATE = 0.3D;
    /** 单工具平均耗时超过该值时视为慢工具 */
    private static final long SLOW_TOOL_AVG_MS = 1000L;
    /** 重复调用明细最多返回的条数 */
    private static final int MAX_DUPLICATE_ITEMS = 10;

    private final AssistantAgentTraceMapper traceMapper;
    private final AssistantChatSessionMapper sessionMapper;

    @Override
    public Profile profile(String sessionId, int limit) {
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        AssistantChatSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AssistantChatSession>()
                .eq(AssistantChatSession::getSessionId, sessionId)
                .eq(AssistantChatSession::getTenantId, tenantId)
                .eq(AssistantChatSession::getUserId, userId));
        if (session == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "会话不存在或不属于当前用户");
        }
        int cap = limit <= 0 ? MAX_TRACE_SCAN : Math.min(limit, MAX_TRACE_SCAN);
        List<AssistantAgentTrace> traces = traceMapper.selectList(new LambdaQueryWrapper<AssistantAgentTrace>()
                .eq(AssistantAgentTrace::getSessionId, session.getSessionId())
                .eq(AssistantAgentTrace::getTenantId, tenantId)
                .orderByAsc(AssistantAgentTrace::getId)
                .last("limit " + cap));

        // 按“工具 + 原始参数”分组，用于识别同参数重复调用
        Map<String, List<AssistantAgentTrace>> grouped = new LinkedHashMap<>();
        Map<String, ToolAcc> toolAccMap = new LinkedHashMap<>();
        long totalLatency = 0L;
        int failedSteps = 0;
        for (AssistantAgentTrace trace : traces) {
            String tool = StrUtil.blankToDefault(trace.getToolName(), "-");
            String args = StrUtil.blankToDefault(trace.getToolArgs(), "{}");
            long latency = trace.getLatencyMs() == null ? 0L : trace.getLatencyMs();
            boolean success = trace.getSuccess() != null && trace.getSuccess() == 1;

            totalLatency += latency;
            if (!success) {
                failedSteps++;
            }
            grouped.computeIfAbsent(tool + "|" + args, key -> new ArrayList<>()).add(trace);

            ToolAcc acc = toolAccMap.computeIfAbsent(tool, key -> new ToolAcc());
            acc.calls++;
            if (!success) {
                acc.failures++;
            }
            acc.totalLatency += latency;
            acc.maxLatency = Math.max(acc.maxLatency, latency);
        }

        int duplicateSteps = 0;
        long wastedLatency = 0L;
        int repeatedFailureGroups = 0;
        List<DuplicateCall> duplicates = new ArrayList<>();
        for (Map.Entry<String, List<AssistantAgentTrace>> entry : grouped.entrySet()) {
            List<AssistantAgentTrace> sameCalls = entry.getValue();
            if (sameCalls.size() < 2) {
                continue;
            }
            long groupWasted = 0L;
            int groupFailures = 0;
            for (int i = 0; i < sameCalls.size(); i++) {
                long latency = sameCalls.get(i).getLatencyMs() == null ? 0L : sameCalls.get(i).getLatencyMs();
                boolean success = sameCalls.get(i).getSuccess() != null && sameCalls.get(i).getSuccess() == 1;
                if (!success) {
                    groupFailures++;
                }
                // 首次调用是必要的，后续同参数调用都算浪费
                if (i > 0) {
                    groupWasted += latency;
                }
            }
            duplicateSteps += sameCalls.size() - 1;
            wastedLatency += groupWasted;
            if (groupFailures >= 2) {
                repeatedFailureGroups++;
            }
            AssistantAgentTrace first = sameCalls.get(0);
            duplicates.add(new DuplicateCall(StrUtil.blankToDefault(first.getToolName(), "-"),
                    StrUtil.maxLength(StrUtil.blankToDefault(first.getToolArgs(), "{}"), 120),
                    sameCalls.size(), groupWasted));
        }
        duplicates.sort(Comparator.comparingLong(DuplicateCall::wastedLatencyMs).reversed());
        if (duplicates.size() > MAX_DUPLICATE_ITEMS) {
            duplicates = new ArrayList<>(duplicates.subList(0, MAX_DUPLICATE_ITEMS));
        }

        List<ToolStat> tools = new ArrayList<>();
        for (Map.Entry<String, ToolAcc> entry : toolAccMap.entrySet()) {
            ToolAcc acc = entry.getValue();
            long avg = acc.calls == 0 ? 0L : acc.totalLatency / acc.calls;
            tools.add(new ToolStat(entry.getKey(), acc.calls, acc.failures,
                    acc.totalLatency, avg, acc.maxLatency));
        }
        tools.sort(Comparator.comparingLong(ToolStat::totalLatencyMs).reversed());

        int steps = traces.size();
        double duplicateRate = steps == 0 ? 0D : round2((double) duplicateSteps / steps);
        double failureRate = steps == 0 ? 0D : (double) failedSteps / steps;
        List<String> suggestions = buildSuggestions(duplicateSteps, duplicateRate, wastedLatency,
                repeatedFailureGroups, failureRate, tools);

        log.info("执行效率画像完成 session={} steps={} duplicates={} wastedMs={}",
                sessionId, steps, duplicateSteps, wastedLatency);
        return new Profile(sessionId, steps, duplicateSteps, duplicateRate, totalLatency,
                wastedLatency, failedSteps, repeatedFailureGroups, toolAccMap.size(),
                tools, duplicates, suggestions);
    }

    /**
     * 确定性建议：只按阈值和实际数据给结论，不调用模型
     */
    private List<String> buildSuggestions(int duplicateSteps, double duplicateRate, long wastedLatency,
                                          int repeatedFailureGroups, double failureRate,
                                          List<ToolStat> tools) {
        List<String> suggestions = new ArrayList<>();
        if (duplicateRate >= HIGH_DUPLICATE_RATE && duplicateSteps > 0) {
            suggestions.add("重复调用 " + duplicateSteps + " 步（占比 " + percent(duplicateRate)
                    + "%），累计浪费 " + wastedLatency + "ms：优先给只读工具加结果缓存，"
                    + "或在编排层识别同参数调用直接复用结果");
        }
        if (repeatedFailureGroups > 0) {
            suggestions.add(repeatedFailureGroups + " 组同参数失败重试属于无效空转："
                    + "建议失败后先归因再决定是否重试，避免重复打同一个坏调用");
        }
        if (failureRate >= HIGH_FAILURE_RATE) {
            suggestions.add("失败步骤占比 " + percent(failureRate) + "%，建议调用失败归因诊断定位根因类别");
        }
        if (!tools.isEmpty() && tools.get(0).avgLatencyMs() >= SLOW_TOOL_AVG_MS) {
            ToolStat slowest = tools.get(0);
            suggestions.add("慢工具 " + slowest.toolName() + " 平均 " + slowest.avgLatencyMs()
                    + "ms（最慢 " + slowest.maxLatencyMs() + "ms）：建议加短 TTL 缓存或改为异步执行");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("未发现明显执行浪费");
        }
        return suggestions;
    }

    private double round2(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private String percent(double rate) {
        return String.valueOf(Math.round(rate * 100D));
    }

    /** 工具累计器 */
    private static final class ToolAcc {
        private int calls;
        private int failures;
        private long totalLatency;
        private long maxLatency;
    }
}
