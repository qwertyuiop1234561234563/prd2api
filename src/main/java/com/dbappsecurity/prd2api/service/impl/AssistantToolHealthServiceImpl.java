package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.entity.AssistantAgentTrace;
import com.dbappsecurity.prd2api.mapper.AssistantAgentTraceMapper;
import com.dbappsecurity.prd2api.service.AssistantToolHealthService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具健康看板实现
 * <p>
 * 判定口径：成功率低于 SLO 下限记 WARN，继续低于 0.8 或 P95 超过上限的两倍记 CRITICAL，
 * 调用次数太少（默认少于 5 次）时不做判级，避免一两次抖动就误报。全部阈值来自配置，
 * 只依赖轨迹，同一条数据任何时候算出的等级一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantToolHealthServiceImpl implements AssistantToolHealthService {

    /** 工具类步骤：只有这两类才计入工具健康 */
    private static final Set<String> TOOL_STEPS = Set.of("TOOL", "KNOWLEDGE");
    private static final int DEFAULT_SCAN = 2000;
    private static final int MAX_SCAN = 5000;
    /** 判为 CRITICAL 的成功率硬下限 */
    private static final double CRITICAL_SUCCESS_RATE = 0.8D;
    /** 判为 CRITICAL 的 P95 倍数（相对 SLO 上限） */
    private static final long CRITICAL_P95_FACTOR = 2L;
    /** 明细最多返回条数 */
    private static final int MAX_LIST = 10;
    private static final int MAX_REASONS = 3;

    private static final Map<String, String> REASON_LABELS = Map.of(
            "INPUT", "入参不合法",
            "TIMEOUT", "下游超时",
            "DOWNSTREAM", "下游不可用",
            "BUSINESS", "工具业务失败");

    private final AssistantAgentTraceMapper traceMapper;
    private final Prd2ApiProperties properties;

    @Override
    public Health health(int days, int limit) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "Agent 未启用");
        }
        int windowDays = days <= 0 ? 7 : Math.min(days, 90);
        int cap = limit <= 0 ? DEFAULT_SCAN : Math.min(limit, MAX_SCAN);
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();

        List<AssistantAgentTrace> traces = traceMapper.selectList(new LambdaQueryWrapper<AssistantAgentTrace>()
                .eq(AssistantAgentTrace::getTenantId, tenantId)
                .eq(AssistantAgentTrace::getUserId, userId)
                .in(AssistantAgentTrace::getStepType, TOOL_STEPS)
                .ge(AssistantAgentTrace::getCreateTime, LocalDateTime.now().minusDays(windowDays))
                .orderByDesc(AssistantAgentTrace::getId)
                .last("limit " + cap));

        Map<String, ToolAcc> accMap = new LinkedHashMap<>();
        List<Long> allLatencies = new ArrayList<>(traces.size());
        int successSteps = 0;
        for (AssistantAgentTrace trace : traces) {
            String tool = StrUtil.blankToDefault(trace.getToolName(), "-");
            long latency = trace.getLatencyMs() == null ? 0L : trace.getLatencyMs();
            boolean success = trace.getSuccess() != null && trace.getSuccess() == 1;
            allLatencies.add(latency);
            if (success) {
                successSteps++;
            }
            ToolAcc acc = accMap.computeIfAbsent(tool, key -> new ToolAcc());
            acc.calls++;
            acc.totalLatency += latency;
            acc.maxLatency = Math.max(acc.maxLatency, latency);
            acc.latencies.add(latency);
            if (!success) {
                acc.failures++;
                String category = classify(StrUtil.blankToDefault(trace.getToolResult(), ""));
                acc.reasons.merge(category, 1, Integer::sum);
            }
        }

        List<ToolHealth> tools = new ArrayList<>(accMap.size());
        for (Map.Entry<String, ToolAcc> entry : accMap.entrySet()) {
            ToolAcc acc = entry.getValue();
            long avg = acc.calls == 0 ? 0L : acc.totalLatency / acc.calls;
            double successRate = acc.calls == 0 ? 1D : (double) (acc.calls - acc.failures) / acc.calls;
            List<Reason> reasons = topReasons(acc.reasons);
            tools.add(new ToolHealth(entry.getKey(), acc.calls, acc.failures, round3(successRate),
                    avg, p95(acc.latencies), acc.maxLatency,
                    grade(acc.calls, successRate, p95(acc.latencies), config), reasons));
        }
        // 判级从重到轻，同级按调用量降序，保证看板头部就是最该处理的工具
        tools.sort(Comparator.comparingInt((ToolHealth t) -> severity(t.sloStatus()))
                .thenComparing(Comparator.comparingInt(ToolHealth::calls).reversed()));

        int unhealthy = (int) tools.stream().filter(t -> !"HEALTHY".equals(t.sloStatus())
                && !"INSUFFICIENT".equals(t.sloStatus())).count();
        // 零样本不能判 HEALTHY：没有数据本身就是一种需要暴露的状态，避免看板给出虚假的绿灯
        String verdict = traces.isEmpty() ? "NO_DATA"
                : (tools.stream().anyMatch(t -> "CRITICAL".equals(t.sloStatus())) ? "DEGRADED"
                : (unhealthy > 0 ? "WATCH" : "HEALTHY"));

        List<ToolHealth> slowest = top(tools, Comparator.comparingLong(ToolHealth::p95LatencyMs).reversed());
        List<ToolHealth> noisiest = top(tools.stream()
                .filter(t -> t.failures() > 0)
                .toList(), Comparator.comparingInt(ToolHealth::failures).reversed());
        double overallSuccess = traces.isEmpty() ? 0D : (double) successSteps / traces.size();

        log.info("工具健康看板完成 window={}d steps={} tools={} verdict={}",
                windowDays, traces.size(), tools.size(), verdict);
        return new Health(windowDays, traces.size(), tools.size(), unhealthy, verdict,
                round3(overallSuccess), p95(allLatencies), tools, slowest, noisiest,
                buildSuggestions(tools, unhealthy, config));
    }

    /**
     * SLO 判级：数据不足不判级，避免刚上线的工具被一两次失败打上红标
     */
    private String grade(int calls, double successRate, long p95, Prd2ApiProperties.Agent config) {
        if (calls < Math.max(1, config.getToolHealthMinCalls())) {
            return "INSUFFICIENT";
        }
        long p95Slo = Math.max(1L, config.getToolHealthP95Ms());
        double successSlo = config.getToolHealthMinSuccessRate();
        if (successRate < CRITICAL_SUCCESS_RATE || p95 > p95Slo * CRITICAL_P95_FACTOR) {
            return "CRITICAL";
        }
        if (successRate < successSlo || p95 > p95Slo) {
            return "WARN";
        }
        return "HEALTHY";
    }

    /**
     * 失败原因确定性分类：与失败归因诊断保持同一套口径，避免两个看板结论打架
     */
    private String classify(String reason) {
        if (reason.contains("参数校验未通过")) {
            return "INPUT";
        }
        if (reason.contains("超时") || reason.contains("timeout")) {
            return "TIMEOUT";
        }
        if (reason.contains("工具执行失败") || reason.contains("工具执行异常")
                || reason.contains("连接") || reason.contains("不可用")) {
            return "DOWNSTREAM";
        }
        return "BUSINESS";
    }

    private List<Reason> topReasons(Map<String, Integer> counts) {
        List<Reason> reasons = new ArrayList<>(counts.size());
        counts.forEach((category, count) ->
                reasons.add(new Reason(category, REASON_LABELS.getOrDefault(category, category), count)));
        reasons.sort(Comparator.comparingInt(Reason::count).reversed());
        return reasons.size() > MAX_REASONS ? new ArrayList<>(reasons.subList(0, MAX_REASONS)) : reasons;
    }

    private List<String> buildSuggestions(List<ToolHealth> tools, int unhealthy,
                                          Prd2ApiProperties.Agent config) {
        List<String> suggestions = new ArrayList<>();
        if (tools.isEmpty()) {
            suggestions.add("统计窗口内没有工具调用轨迹，先正常对话几轮再看健康度");
            return suggestions;
        }
        for (ToolHealth tool : tools) {
            if (!"CRITICAL".equals(tool.sloStatus())) {
                continue;
            }
            String reason = tool.topFailures().isEmpty() ? "失败原因未知"
                    : tool.topFailures().get(0).label() + " " + tool.topFailures().get(0).count() + " 次";
            suggestions.add("工具 " + tool.toolName() + " 判为 CRITICAL：成功率 "
                    + percent(tool.successRate()) + "%，P95 " + tool.p95LatencyMs()
                    + "ms，首要失败原因 " + reason + "；建议先按失败归因诊断定位再决定是否降级");
        }
        if (unhealthy > 0 && suggestions.isEmpty()) {
            suggestions.add(unhealthy + " 个工具低于 SLO（成功率 < " + percent(config.getToolHealthMinSuccessRate())
                    + "% 或 P95 > " + config.getToolHealthP95Ms() + "ms），建议纳入发布前回归门禁");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("全部工具满足 SLO（成功率 ≥ " + percent(config.getToolHealthMinSuccessRate())
                    + "%，P95 ≤ " + config.getToolHealthP95Ms() + "ms）");
        }
        return suggestions;
    }

    private List<ToolHealth> top(List<ToolHealth> tools, Comparator<ToolHealth> comparator) {
        List<ToolHealth> sorted = new ArrayList<>(tools);
        sorted.sort(comparator);
        return sorted.size() > MAX_LIST ? new ArrayList<>(sorted.subList(0, MAX_LIST)) : sorted;
    }

    /**
     * P95 采用最近秩法：排序后取 ceil(0.95 * n) 位置，样本少时结果稳定可解释
     */
    private long p95(List<Long> latencies) {
        if (latencies.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(0.95D * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private int severity(String status) {
        return switch (status) {
            case "CRITICAL" -> 0;
            case "WARN" -> 1;
            case "HEALTHY" -> 2;
            default -> 3;
        };
    }

    private double round3(double value) {
        return Math.round(value * 1000D) / 1000D;
    }

    private String percent(double rate) {
        return String.valueOf(Math.round(rate * 100D));
    }

    /** 单工具累计器 */
    private static final class ToolAcc {
        private int calls;
        private int failures;
        private long totalLatency;
        private long maxLatency;
        private final List<Long> latencies = new ArrayList<>();
        private final Map<String, Integer> reasons = new HashMap<>();
    }
}
