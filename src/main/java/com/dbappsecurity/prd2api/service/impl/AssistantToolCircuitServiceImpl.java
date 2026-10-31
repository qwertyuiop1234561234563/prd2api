package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolRegistry;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.entity.AssistantAgentTrace;
import com.dbappsecurity.prd2api.mapper.AssistantAgentTraceMapper;
import com.dbappsecurity.prd2api.service.AssistantToolCircuitService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 工具依赖熔断实现
 * <p>
 * 状态机：CLOSED（正常）→ OPEN（窗口内失败率超阈值且仍在冷却期）→ HALF_OPEN（冷却结束，只放行一次探测）。
 * 判定数据直接取真实调用轨迹，不额外维护计数器，避免“计数器与轨迹不一致”这种典型双写问题；
 * 快照缓存在 Redis 里，执行器每次调用只读缓存，不会为每次工具调用都查库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantToolCircuitServiceImpl implements AssistantToolCircuitService {

    private static final int MAX_SCAN = 5000;
    private static final String CLOSED = "CLOSED";
    private static final String OPEN = "OPEN";
    private static final String HALF_OPEN = "HALF_OPEN";
    /** 轨迹回溯窗口：熔断只看最近一天，避免历史故障长期影响新版本 */
    private static final int TRACE_DAYS = 1;

    private final AssistantAgentTraceMapper traceMapper;
    private final AssistantToolRegistry toolRegistry;
    private final Prd2ApiProperties properties;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Decision allow(String toolName) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isCircuitEnabled() || StrUtil.isBlank(toolName)) {
            return new Decision(true, CLOSED, "");
        }
        ToolState state = snapshot(false).tools().stream()
                .filter(tool -> toolName.equals(tool.toolName()))
                .findFirst().orElse(null);
        if (state == null || CLOSED.equals(state.state())) {
            return new Decision(true, CLOSED, "");
        }
        long cooldown = Math.max(1L, config.getCircuitCooldownSeconds());
        if (OPEN.equals(state.state())) {
            long remaining = Math.max(0L, cooldown - state.lastFailureAgeMs() / 1000L);
            return new Decision(false, OPEN, "工具近期失败率 " + percent(state.failureRate()) + "%（"
                    + state.calls() + " 次调用），已熔断，约 " + remaining + " 秒后进入半开探测");
        }
        // 半开：冷却周期内只发一张探测票据，避免恢复流量瞬间再次打垮下游
        String probeKey = RedisKeyConstant.assistantCircuitProbe(
                TenantContextUtil.requireTenantId(), TenantContextUtil.requireUserKey(), toolName);
        try {
            Boolean granted = stringRedisTemplate.opsForValue().setIfAbsent(
                    probeKey, String.valueOf(System.currentTimeMillis()), cooldown, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(granted)) {
                log.info("工具半开探测放行 tool={} cooldown={}s", toolName, cooldown);
                return new Decision(true, HALF_OPEN, "半开探测放行（本冷却周期仅一次）");
            }
            return new Decision(false, HALF_OPEN, "半开探测已在进行中，等待本次探测结果");
        } catch (Exception e) {
            // 熔断是保护手段，Redis 不可用时不能反过来阻断业务
            log.debug("熔断探测票据写入失败，按放行处理 msg={}", e.getMessage());
            return new Decision(true, HALF_OPEN, "");
        }
    }

    @Override
    public Snapshot snapshot(boolean refresh) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isCircuitEnabled()) {
            return new Snapshot(0, 0, 0D, 0L, List.of(), List.of("工具熔断已关闭"));
        }
        String key = RedisKeyConstant.assistantCircuit(
                TenantContextUtil.requireTenantId(), TenantContextUtil.requireUserKey());
        if (!refresh) {
            Snapshot cached = readCache(key);
            if (cached != null) {
                return cached;
            }
        }
        Snapshot snapshot = compute(config);
        writeCache(key, snapshot, Math.max(1L, config.getCircuitCacheTtlSeconds()));
        return snapshot;
    }

    private Snapshot compute(Prd2ApiProperties.Agent config) {
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        int window = Math.max(1, config.getCircuitWindowCalls());
        int toolCount = Math.max(1, toolRegistry.names().size());
        int limit = Math.min(MAX_SCAN, Math.max(window, window * toolCount));
        List<AssistantAgentTrace> traces = traceMapper.selectList(new LambdaQueryWrapper<AssistantAgentTrace>()
                .eq(AssistantAgentTrace::getTenantId, tenantId)
                .eq(AssistantAgentTrace::getUserId, userId)
                .eq(AssistantAgentTrace::getStepType, "TOOL")
                .ge(AssistantAgentTrace::getCreateTime, LocalDateTime.now().minusDays(TRACE_DAYS))
                .orderByDesc(AssistantAgentTrace::getId)
                .last("limit " + limit));

        Map<String, Acc> accMap = new LinkedHashMap<>();
        for (AssistantAgentTrace trace : traces) {
            String tool = StrUtil.blankToDefault(trace.getToolName(), "");
            if (StrUtil.isBlank(tool)) {
                continue;
            }
            Acc acc = accMap.computeIfAbsent(tool, key -> new Acc());
            if (acc.calls >= window) {
                // 每个工具只看最近 window 次，避免老故障长期占满窗口
                continue;
            }
            acc.calls++;
            if (trace.getSuccess() == null || trace.getSuccess() == 0) {
                acc.failures++;
                if (trace.getCreateTime() != null) {
                    acc.lastFailureMs = Math.max(acc.lastFailureMs, timestamp(trace.getCreateTime()));
                }
            }
        }

        Set<String> names = new LinkedHashSet<>(toolRegistry.names());
        names.addAll(accMap.keySet());
        int minCalls = Math.max(1, config.getCircuitMinCalls());
        double threshold = config.getCircuitFailureRate();
        long cooldown = Math.max(1L, config.getCircuitCooldownSeconds());
        long now = System.currentTimeMillis();
        List<ToolState> states = new ArrayList<>(names.size());
        for (String name : names) {
            Acc acc = accMap.getOrDefault(name, new Acc());
            double rate = acc.calls == 0 ? 0D : (double) acc.failures / acc.calls;
            String state = CLOSED;
            if (acc.calls >= minCalls && rate >= threshold) {
                state = acc.lastFailureMs > 0 && now - acc.lastFailureMs >= cooldown * 1000L ? HALF_OPEN : OPEN;
            }
            long age = acc.lastFailureMs > 0 ? Math.max(0L, now - acc.lastFailureMs) : -1L;
            boolean probeTaken = probeTaken(name);
            states.add(new ToolState(name, state, acc.calls, acc.failures, round2(rate), age, probeTaken));
        }
        states.sort(Comparator.comparingInt((ToolState state) -> severity(state.state()))
                .thenComparing(Comparator.comparingDouble(ToolState::failureRate).reversed()));

        List<String> notes = new ArrayList<>();
        notes.add("熔断基于最近 " + window + " 次真实调用轨迹，窗口内少于 " + minCalls + " 次不判定，避免小样本误伤");
        notes.add("失败率达到 " + percent(threshold) + "% 触发熔断，冷却 " + cooldown + " 秒后进入半开，只放行一次探测");
        return new Snapshot(window, minCalls, threshold, cooldown, states, notes);
    }

    private boolean probeTaken(String toolName) {
        try {
            String key = RedisKeyConstant.assistantCircuitProbe(
                    TenantContextUtil.requireTenantId(), TenantContextUtil.requireUserKey(), toolName);
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        } catch (Exception e) {
            return false;
        }
    }

    private Snapshot readCache(String key) {
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            return StrUtil.isBlank(cached) ? null : objectMapper.readValue(cached, Snapshot.class);
        } catch (Exception e) {
            log.debug("熔断快照缓存读取失败 msg={}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, Snapshot snapshot, long ttlSeconds) {
        try {
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(snapshot),
                    ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("熔断快照缓存写入失败 msg={}", e.getMessage());
        }
    }

    private int severity(String state) {
        return OPEN.equals(state) ? 0 : (HALF_OPEN.equals(state) ? 1 : 2);
    }

    private long timestamp(LocalDateTime time) {
        return time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String percent(double rate) {
        return String.format("%.0f", rate * 100D);
    }

    private double round2(double value) {
        return Math.round(value * 100D) / 100D;
    }

    /**
     * 单工具窗口累计器
     */
    private static final class Acc {
        private int calls;
        private int failures;
        private long lastFailureMs;
    }
}
