package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.entity.AssistantAgentTrace;
import com.dbappsecurity.prd2api.entity.AssistantChatSession;
import com.dbappsecurity.prd2api.mapper.AssistantAgentTraceMapper;
import com.dbappsecurity.prd2api.mapper.AssistantChatSessionMapper;
import com.dbappsecurity.prd2api.service.AssistantAuditService;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 决策审计实现
 * <p>
 * 轨迹按 traceId 聚成一“轮”，逐轮校验三条硬规则：
 * 1) 工具失败后必须有 REFLECT 步骤（失败必须被诊断，不能静默吞掉）；
 * 2) 危险工具（取消/重试任务）执行前必须有 CONFIRM 步骤（人工确认不可绕过）；
 * 3) 知识检索标记成功时结果不能为空或“证据不足”（防止伪成功）。
 * 另把契约校验降级单独统计为“已降级”而非违规，避免把正常兜底误判成问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantAuditServiceImpl implements AssistantAuditService {

    /** 危险工具：必须经过人工确认才能执行 */
    private static final Set<String> DANGER_TOOLS = Set.of("cancel_task", "retry_task");
    /** 允许出错的工具步骤类型 */
    private static final Set<String> TOOL_STEPS = Set.of("TOOL", "KNOWLEDGE");
    private static final int MAX_TRACE_SCAN = 200;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AssistantAgentTraceMapper traceMapper;
    private final AssistantChatSessionMapper sessionMapper;
    private final MetricUtil metricUtil;
    private final Prd2ApiProperties properties;

    @Override
    public Report audit(String sessionId, int limit) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isAuditEnabled()) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "决策审计已关闭");
        }
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

        Map<String, List<AssistantAgentTrace>> rounds = new LinkedHashMap<>();
        List<TimelineItem> timeline = new ArrayList<>();
        List<Violation> violations = new ArrayList<>();
        long totalLatency = 0L;
        long slowest = 0L;
        for (AssistantAgentTrace trace : traces) {
            String round = roundKey(trace.getTraceId());
            rounds.computeIfAbsent(round, key -> new ArrayList<>()).add(trace);
            long latency = trace.getLatencyMs() == null ? 0L : trace.getLatencyMs();
            totalLatency += latency;
            slowest = Math.max(slowest, latency);
            timeline.add(new TimelineItem(trace.getStepNo() == null ? 0 : trace.getStepNo(), round,
                    StrUtil.blankToDefault(trace.getStepType(), ""), StrUtil.blankToDefault(trace.getToolName(), ""),
                    trace.getSuccess() != null && trace.getSuccess() == 1, latency,
                    StrUtil.maxLength(trace.getToolResult(), 120),
                    trace.getCreateTime() == null ? "" : trace.getCreateTime().format(TIME_FORMAT)));
        }

        int reflectPass = 0;
        int reflectFail = 0;
        int evidencePass = 0;
        int evidenceFail = 0;
        int contractDegraded = 0;
        for (Map.Entry<String, List<AssistantAgentTrace>> entry : rounds.entrySet()) {
            List<AssistantAgentTrace> round = entry.getValue();
            boolean hasFailedTool = round.stream()
                    .anyMatch(trace -> TOOL_STEPS.contains(StrUtil.blankToDefault(trace.getStepType(), ""))
                            && trace.getSuccess() != null && trace.getSuccess() == 0);
            boolean hasReflect = round.stream().anyMatch(trace -> "REFLECT".equals(trace.getStepType()));
            if (hasFailedTool) {
                String failedTool = round.stream()
                        .filter(trace -> TOOL_STEPS.contains(StrUtil.blankToDefault(trace.getStepType(), ""))
                                && trace.getSuccess() != null && trace.getSuccess() == 0)
                        .map(AssistantAgentTrace::getToolName).filter(StrUtil::isNotBlank).findFirst().orElse("unknown");
                if (hasReflect) {
                    reflectPass++;
                } else {
                    reflectFail++;
                    violations.add(new Violation(entry.getKey(), "FAILURE_REFLECTION",
                            "工具 " + failedTool + " 失败后没有反思步骤"));
                }
            }
            for (AssistantAgentTrace trace : round) {
                if ("CONTRACT".equals(trace.getStepType()) && trace.getSuccess() != null && trace.getSuccess() == 0) {
                    contractDegraded++;
                }
                if (!"KNOWLEDGE".equals(trace.getStepType()) || trace.getSuccess() == null || trace.getSuccess() != 1) {
                    continue;
                }
                String result = StrUtil.blankToDefault(trace.getToolResult(), "");
                if (StrUtil.isBlank(result) || result.contains("知识库证据不足")) {
                    evidenceFail++;
                    violations.add(new Violation(entry.getKey(), "KNOWLEDGE_EVIDENCE",
                            "知识检索标记成功但结果为空或证据不足"));
                } else {
                    evidencePass++;
                }
            }
        }

        // 危险操作确认：CONFIRM 步骤必须出现在同一会话中该工具执行之前
        Set<String> confirmed = new HashSet<>();
        int confirmPass = 0;
        int confirmFail = 0;
        for (AssistantAgentTrace trace : traces) {
            String tool = StrUtil.blankToDefault(trace.getToolName(), "");
            if ("CONFIRM".equals(trace.getStepType()) && StrUtil.isNotBlank(tool)) {
                confirmed.add(tool);
                continue;
            }
            if (!"TOOL".equals(trace.getStepType()) || !DANGER_TOOLS.contains(tool)) {
                continue;
            }
            if (confirmed.contains(tool)) {
                confirmPass++;
            } else {
                confirmFail++;
                violations.add(new Violation(roundKey(trace.getTraceId()), "DANGER_CONFIRM",
                        "危险工具 " + tool + " 执行前没有人工确认步骤"));
            }
        }

        List<Check> checks = new ArrayList<>();
        checks.add(new Check("FAILURE_REFLECTION", "失败后必须反思", reflectPass, reflectFail,
                "有失败但缺反思的轮次：" + reflectFail));
        checks.add(new Check("KNOWLEDGE_EVIDENCE", "知识成功必须有证据", evidencePass, evidenceFail,
                "证据不足却标记成功的检索：" + evidenceFail));
        checks.add(new Check("DANGER_CONFIRM", "危险操作必须先确认", confirmPass, confirmFail,
                "绕过确认的危险执行：" + confirmFail));
        checks.add(new Check("CONTRACT", "工具输出契约", contractDegraded, 0,
                "契约不合规并已降级的步骤：" + contractDegraded));

        int passed = reflectPass + evidencePass + confirmPass;
        int failed = reflectFail + evidenceFail + confirmFail;
        double score = passed + failed == 0 ? 1.0D
                : Math.round(((double) passed / (passed + failed)) * 100) / 100.0D;
        metricUtil.agentAudit(violations.isEmpty() ? "clean" : "violation");
        for (Violation violation : violations) {
            metricUtil.agentAuditViolation(violation.rule());
        }
        log.info("决策审计完成 session={} rounds={} steps={} violations={} score={}",
                sessionId, rounds.size(), traces.size(), violations.size(), score);
        return new Report(sessionId, rounds.size(), traces.size(), violations.size(), score,
                totalLatency, slowest, checks, violations, timeline);
    }

    private String roundKey(String traceId) {
        return StrUtil.isBlank(traceId) ? "unknown"
                : traceId.length() > 8 ? traceId.substring(0, 8) : traceId;
    }
}
