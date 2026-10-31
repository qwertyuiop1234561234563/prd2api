package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.entity.AssistantAgentTrace;
import com.dbappsecurity.prd2api.entity.AssistantChatMessage;
import com.dbappsecurity.prd2api.mapper.AssistantAgentTraceMapper;
import com.dbappsecurity.prd2api.mapper.AssistantChatMessageMapper;
import com.dbappsecurity.prd2api.service.AssistantPiiAuditService;
import com.dbappsecurity.prd2api.util.PiiMaskUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存量 PII 泄漏审计实现
 * <p>
 * 只做确定性扫描：复用写入链路同一套 PiiMaskUtil 规则，保证“审计口径 = 脱敏口径”，
 * 不会出现审计说有问题、修复却改不掉的错位。修复按行回写，打码文本再扫一遍不会再次命中，
 * 因此接口可重复调用且幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantPiiAuditServiceImpl implements AssistantPiiAuditService {

    private static final int DEFAULT_SCAN = 1000;
    private static final int MAX_SCAN = 5000;
    /** 泄漏明细最多返回条数，避免响应过大 */
    private static final int MAX_FINDINGS = 50;
    private static final int PREVIEW_CHARS = 200;

    /** 类型 → 中文标签 */
    private static final Map<String, String> TYPE_LABELS = Map.of(
            "phone", "手机号",
            "idCard", "身份证",
            "bankCard", "银行卡",
            "email", "邮箱");
    /** 高危类型：一旦明文落库需立即修复 */
    private static final Map<String, String> HIGH_TYPES = Map.of(
            "idCard", "身份证",
            "bankCard", "银行卡");

    private final AssistantChatMessageMapper messageMapper;
    private final AssistantAgentTraceMapper traceMapper;
    private final Prd2ApiProperties properties;

    @Override
    public Report audit(int limit) {
        ensureEnabled();
        int cap = cap(limit);
        Ctx ctx = requireCtx();

        List<Finding> findings = new ArrayList<>();
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        int leakedRows = 0;
        int piiHits = 0;

        List<AssistantChatMessage> messages = scanMessages(ctx, cap);
        for (AssistantChatMessage message : messages) {
            PiiMaskUtil.MaskedText masked = PiiMaskUtil.maskAndCount(message.getContent());
            if (masked.total() <= 0) {
                continue;
            }
            leakedRows++;
            piiHits += masked.total();
            merge(typeCounts, masked.hits());
            addFinding(findings, "MESSAGE", message.getId(), message.getSessionId(),
                    "content", masked);
        }

        List<AssistantAgentTrace> traces = scanTraces(ctx, cap);
        for (AssistantAgentTrace trace : traces) {
            PiiMaskUtil.MaskedText args = PiiMaskUtil.maskAndCount(trace.getToolArgs());
            PiiMaskUtil.MaskedText result = PiiMaskUtil.maskAndCount(trace.getToolResult());
            int hits = args.total() + result.total();
            if (hits <= 0) {
                continue;
            }
            leakedRows++;
            piiHits += hits;
            if (args.total() > 0) {
                merge(typeCounts, args.hits());
                addFinding(findings, "TRACE", trace.getId(), trace.getSessionId(), "toolArgs", args);
            }
            if (result.total() > 0) {
                merge(typeCounts, result.hits());
                addFinding(findings, "TRACE", trace.getId(), trace.getSessionId(), "toolResult", result);
            }
        }

        int scannedRows = messages.size() + traces.size();
        double leakRate = scannedRows == 0 ? 0D : (double) leakedRows / scannedRows;
        String verdict = leakedRows == 0 ? "CLEAN"
                : (findings.stream().anyMatch(f -> "HIGH".equals(f.severity())) ? "HIGH_RISK" : "LEAK_FOUND");
        log.info("PII 审计完成 messages={} traces={} leaked={} hits={}",
                messages.size(), traces.size(), leakedRows, piiHits);
        return new Report(messages.size(), traces.size(), leakedRows, piiHits, round3(leakRate),
                typeCounts(typeCounts), findings, buildSuggestions(verdict, findings), verdict);
    }

    @Override
    public Repair repair(int limit) {
        ensureEnabled();
        int cap = cap(limit);
        Ctx ctx = requireCtx();
        int repairedRows = 0;
        int maskedFields = 0;
        int scannedRows = 0;

        List<AssistantChatMessage> messages = scanMessages(ctx, cap);
        scannedRows += messages.size();
        for (AssistantChatMessage message : messages) {
            PiiMaskUtil.MaskedText masked = PiiMaskUtil.maskAndCount(message.getContent());
            if (masked.total() <= 0) {
                continue;
            }
            AssistantChatMessage update = new AssistantChatMessage();
            update.setId(message.getId());
            update.setContent(masked.text());
            messageMapper.updateById(update);
            repairedRows++;
            maskedFields++;
        }

        List<AssistantAgentTrace> traces = scanTraces(ctx, cap);
        scannedRows += traces.size();
        for (AssistantAgentTrace trace : traces) {
            AssistantAgentTrace update = new AssistantAgentTrace();
            update.setId(trace.getId());
            boolean touched = false;
            PiiMaskUtil.MaskedText args = PiiMaskUtil.maskAndCount(trace.getToolArgs());
            if (args.total() > 0) {
                update.setToolArgs(args.text());
                maskedFields++;
                touched = true;
            }
            PiiMaskUtil.MaskedText result = PiiMaskUtil.maskAndCount(trace.getToolResult());
            if (result.total() > 0) {
                update.setToolResult(result.text());
                maskedFields++;
                touched = true;
            }
            if (touched) {
                traceMapper.updateById(update);
                repairedRows++;
            }
        }

        log.info("PII 修复完成 rows={} fields={}", repairedRows, maskedFields);
        List<String> remaining = repairedRows == 0
                ? List.of("本轮扫描范围内没有需要修复的明文 PII")
                : List.of("已按审计规则回写 " + maskedFields + " 个字段；建议重新执行审计确认归零，"
                + "并在写入链路补上脱敏门禁防止再次回流");
        return new Repair(scannedRows, repairedRows, maskedFields, remaining);
    }

    private void addFinding(List<Finding> findings, String source, Long rowId, String sessionId,
                            String field, PiiMaskUtil.MaskedText masked) {
        if (findings.size() >= MAX_FINDINGS) {
            return;
        }
        String severity = masked.hits().keySet().stream().anyMatch(HIGH_TYPES::containsKey) ? "HIGH" : "MEDIUM";
        findings.add(new Finding(source, rowId, sessionId, field, masked.hits(),
                StrUtil.maxLength(masked.text(), PREVIEW_CHARS), severity, true));
    }

    private List<TypeCount> typeCounts(Map<String, Integer> counts) {
        List<TypeCount> result = new ArrayList<>(counts.size());
        counts.forEach((type, count) -> result.add(new TypeCount(type,
                TYPE_LABELS.getOrDefault(type, type),
                count, HIGH_TYPES.containsKey(type) ? "HIGH" : "MEDIUM")));
        result.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return result;
    }

    private List<String> buildSuggestions(String verdict, List<Finding> findings) {
        List<String> suggestions = new ArrayList<>();
        if ("CLEAN".equals(verdict)) {
            suggestions.add("扫描范围内未发现未脱敏的手机号/身份证/银行卡/邮箱，写入链路脱敏有效");
            return suggestions;
        }
        if ("HIGH_RISK".equals(verdict)) {
            suggestions.add("存在身份证/银行卡明文落库，属高危：立即执行一键修复，并排查绕过脱敏的导入链路");
        }
        suggestions.add("共 " + findings.size() + " 条泄漏明细（最多展示 50 条），可执行一键修复按同一规则回写打码");
        suggestions.add("建议把本审计接入发布门禁：命中高危类型直接阻断上线");
        return suggestions;
    }

    private List<AssistantChatMessage> scanMessages(Ctx ctx, int cap) {
        return messageMapper.selectList(new LambdaQueryWrapper<AssistantChatMessage>()
                .eq(AssistantChatMessage::getTenantId, ctx.tenantId())
                .eq(AssistantChatMessage::getUserId, ctx.userId())
                .orderByDesc(AssistantChatMessage::getId)
                .last("limit " + cap));
    }

    private List<AssistantAgentTrace> scanTraces(Ctx ctx, int cap) {
        return traceMapper.selectList(new LambdaQueryWrapper<AssistantAgentTrace>()
                .eq(AssistantAgentTrace::getTenantId, ctx.tenantId())
                .eq(AssistantAgentTrace::getUserId, ctx.userId())
                .orderByDesc(AssistantAgentTrace::getId)
                .last("limit " + cap));
    }

    private void ensureEnabled() {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isPiiAuditEnabled()) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "PII 存量审计已关闭");
        }
    }

    private Ctx requireCtx() {
        return new Ctx(TenantContextUtil.requireTenantId(), TenantContextUtil.requireUserId());
    }

    private int cap(int limit) {
        return limit <= 0 ? DEFAULT_SCAN : Math.min(limit, MAX_SCAN);
    }

    private void merge(Map<String, Integer> target, Map<String, Integer> source) {
        source.forEach((type, count) -> target.merge(type, count, Integer::sum));
    }

    private double round3(double value) {
        return Math.round(value * 1000D) / 1000D;
    }

    private record Ctx(String tenantId, String userId) {
    }
}
