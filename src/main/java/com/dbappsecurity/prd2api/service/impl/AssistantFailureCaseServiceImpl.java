package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolExecutor;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.entity.AssistantAgentTrace;
import com.dbappsecurity.prd2api.entity.AssistantChatMessage;
import com.dbappsecurity.prd2api.mapper.AssistantAgentTraceMapper;
import com.dbappsecurity.prd2api.mapper.AssistantChatMessageMapper;
import com.dbappsecurity.prd2api.service.AssistantFailureCaseService;
import com.dbappsecurity.prd2api.service.LlmInvokeService.ToolCall;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 失败样本挖掘实现
 * <p>
 * 数据流：失败轨迹 → 按“工具 + 错误特征”聚簇 → 关联该会话最近的用户问题 → 存入 Redis 候选集；
 * 重放时用当前工具链执行原参数，成功即“已修复”，仍失败即“仍然存在”。
 * 这套机制把线上失败直接变成可重复执行的回归用例，避免同类问题反复出现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantFailureCaseServiceImpl implements AssistantFailureCaseService {

    private static final int MAX_SCAN = 300;
    private static final int MAX_GROUPS = 20;
    private static final int MAX_CASES = 50;
    private static final int TTL_DAYS = 30;
    private static final long TTL_SECONDS = TimeUnit.DAYS.toSeconds(TTL_DAYS);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 只沉淀只读工具的失败样本：写/危险工具的参数不能进入可重放集合，避免误触发副作用 */
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "search_knowledge", "recall_memory", "query_my_tasks", "get_task_status", "query_my_quota",
            "query_my_vip", "query_admin_stats", "list_my_files");

    private final AssistantAgentTraceMapper traceMapper;
    private final AssistantChatMessageMapper messageMapper;
    private final AssistantToolExecutor toolExecutor;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final MetricUtil metricUtil;

    @Override
    public MineReport mine(int limit) {
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        int cap = Math.min(MAX_GROUPS, Math.max(1, limit));
        LocalDateTime from = LocalDateTime.now().minusDays(7);
        List<AssistantAgentTrace> traces = traceMapper.selectList(
                new LambdaQueryWrapper<AssistantAgentTrace>()
                        .eq(AssistantAgentTrace::getTenantId, tenantId)
                        .eq(AssistantAgentTrace::getSuccess, 0)
                        .ge(AssistantAgentTrace::getCreateTime, from)
                        .in(AssistantAgentTrace::getStepType, List.of("TOOL", "KNOWLEDGE"))
                        .orderByDesc(AssistantAgentTrace::getCreateTime)
                        .last("limit " + MAX_SCAN));
        Map<String, List<AssistantAgentTrace>> groups = new LinkedHashMap<>();
        for (AssistantAgentTrace trace : traces) {
            if (StrUtil.isBlank(trace.getToolName()) || !READ_ONLY_TOOLS.contains(trace.getToolName())) {
                continue;
            }
            groups.computeIfAbsent(signature(trace), key -> new ArrayList<>()).add(trace);
        }
        List<Case> cases = new ArrayList<>();
        List<AssistantAgentTrace> picked = groups.values().stream().limit(cap).map(list -> list.get(0)).toList();
        for (AssistantAgentTrace trace : picked) {
            String groupKey = signature(trace);
            Case candidate = new Case(DigestUtil.md5Hex(groupKey).substring(0, 12),
                    questionOf(trace.getSessionId()), trace.getToolName(),
                    StrUtil.blankToDefault(trace.getToolArgs(), "{}"),
                    StrUtil.maxLength(trace.getToolResult(), 160),
                    groups.get(groupKey).size(), LocalDateTime.now().format(TIME_FORMAT), "MINE");
            cases.add(candidate);
        }
        int stored = save(cases);
        metricUtil.agentFailureCase("mined", stored);
        log.info("失败样本挖掘完成 scanned={} groups={} stored={}", traces.size(), groups.size(), stored);
        return new MineReport(traces.size(), groups.size(), stored, cases);
    }

    @Override
    public int store(List<Case> cases) {
        return save(cases);
    }

    @Override
    public List<Case> candidates() {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(caseKey());
        List<Case> result = new ArrayList<>();
        for (Object value : entries.values()) {
            Case item = read(value);
            if (item != null) {
                result.add(item);
            }
        }
        result.sort((left, right) -> right.createdAt().compareTo(left.createdAt()));
        return result;
    }

    @Override
    public RunReport run() {
        List<Case> cases = candidates();
        List<RunItem> items = new ArrayList<>();
        int fixed = 0;
        int skipped = 0;
        for (Case candidate : cases) {
            if (!READ_ONLY_TOOLS.contains(candidate.toolName())) {
                skipped++;
                continue;
            }
            long start = System.currentTimeMillis();
            ToolCall call = new ToolCall("case_" + candidate.id(), candidate.toolName(), candidate.arguments());
            AssistantToolExecutor.Execution execution = toolExecutor.executeAll(List.of(call)).get(0);
            long latency = execution.latencyMs() > 0 ? execution.latencyMs() : System.currentTimeMillis() - start;
            boolean ok = execution.result().success();
            if (ok) {
                fixed++;
            }
            metricUtil.agentFailureCase(ok ? "fixed" : "open", 1);
            items.add(new RunItem(candidate.id(), candidate.question(), candidate.toolName(), ok,
                    StrUtil.maxLength(execution.result().summary(), 160), latency));
        }
        int open = items.size() - fixed;
        log.info("失败用例重放完成 total={} fixed={} open={} skipped={}", items.size(), fixed, open, skipped);
        return new RunReport(items.size(), fixed, open, skipped, items);
    }

    private String questionOf(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return "";
        }
        AssistantChatMessage message = messageMapper.selectOne(
                new LambdaQueryWrapper<AssistantChatMessage>()
                        .eq(AssistantChatMessage::getSessionId, sessionId)
                        .eq(AssistantChatMessage::getRole, AssistantChatMessage.ROLE_USER)
                        .orderByDesc(AssistantChatMessage::getId)
                        .last("limit 1"));
        return message == null ? "" : StrUtil.maxLength(message.getContent(), 80);
    }

    /**
     * 错误特征归一化：去掉数字与长 ID，避免同一类错误被拆成大量样本
     */
    private String signature(AssistantAgentTrace trace) {
        String error = StrUtil.blankToDefault(trace.getToolResult(), "")
                .replaceAll("[0-9a-fA-F]{8,}", "{id}")
                .replaceAll("\\d+", "{n}");
        error = StrUtil.blankToDefault(error, "").replaceAll("\\s+", "");
        if (error.length() > 60) {
            error = error.substring(0, 60);
        }
        return StrUtil.blankToDefault(trace.getToolName(), "") + "|" + error;
    }

    private int save(List<Case> cases) {
        String key = caseKey();
        int stored = 0;
        for (Case candidate : cases) {
            try {
                stringRedisTemplate.opsForHash().put(key, candidate.id(), objectMapper.writeValueAsString(candidate));
                stored++;
            } catch (Exception e) {
                log.warn("失败用例写入失败 id={} msg={}", candidate.id(), e.getMessage());
            }
        }
        if (stored > 0) {
            stringRedisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
        }
        trim(key);
        return stored;
    }

    /**
     * 候选集清理：先剔除历史遗留的非只读用例（旧版本可能存过写/危险工具参数），
     * 再按时间排序只保留最近 N 条，防止 Redis 无限增长
     */
    private void trim(String key) {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        List<Case> all = new ArrayList<>();
        for (Object value : entries.values()) {
            Case item = read(value);
            if (item == null) {
                continue;
            }
            if (!READ_ONLY_TOOLS.contains(item.toolName())) {
                stringRedisTemplate.opsForHash().delete(key, item.id());
                continue;
            }
            all.add(item);
        }
        if (all.size() <= MAX_CASES) {
            return;
        }
        all.sort((left, right) -> left.createdAt().compareTo(right.createdAt()));
        int remove = all.size() - MAX_CASES;
        for (int i = 0; i < remove; i++) {
            stringRedisTemplate.opsForHash().delete(key, all.get(i).id());
        }
    }

    private Case read(Object value) {
        try {
            Case item = objectMapper.readValue(String.valueOf(value), Case.class);
            // 兼容旧数据：早期用例没有 source 字段，按失败挖掘归类
            return StrUtil.isBlank(item.source()) ? new Case(item.id(), item.question(), item.toolName(),
                    item.arguments(), item.error(), item.count(), item.createdAt(), "MINE") : item;
        } catch (Exception e) {
            return null;
        }
    }

    private String caseKey() {
        String tenantId = TenantContextUtil.requireTenantId();
        String userKey = StrUtil.blankToDefault(TenantContextUtil.getUserKey(),
                TenantContextUtil.requireUserId());
        return RedisKeyConstant.ASSISTANT_FAILURE_CASE_PREFIX + tenantId + ":" + userKey;
    }
}
