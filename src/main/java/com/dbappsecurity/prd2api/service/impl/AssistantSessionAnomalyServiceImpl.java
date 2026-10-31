package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.entity.AssistantAgentTrace;
import com.dbappsecurity.prd2api.mapper.AssistantAgentTraceMapper;
import com.dbappsecurity.prd2api.service.AssistantSessionAnomalyService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话行为异常检测实现
 * <p>
 * 统计口径：以会话为单位聚合步数、失败率、同参数重复率、工具多样性与 P95 延迟，
 * 用中位数 + MAD 算稳健 z 分数找离群值；失败率/重复率这类基线常为 0 的指标，
 * z 分数会被零中位数放大，因此同时保留绝对阈值兜底，两者任一命中即报警。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantSessionAnomalyServiceImpl implements AssistantSessionAnomalyService {

    private static final Set<String> TOOL_STEPS = Set.of("TOOL", "KNOWLEDGE");
    private static final int DEFAULT_SCAN = 2000;
    private static final int MAX_SCAN = 5000;
    private static final int MAX_ANOMALIES = 20;
    /** 绝对阈值：失败率超过 40% 直接告警 */
    private static final double ABS_FAILURE_RATE = 0.4D;
    /** 绝对阈值：同参数重复占比超过 30% 直接告警 */
    private static final double ABS_DUPLICATE_RATE = 0.3D;
    /** 失败率超过该值判为高危会话 */
    private static final double HIGH_FAILURE_RATE = 0.6D;
    /**
     * 评分中单个 z 分数的上限：基线样本少时 MAD 可能极小，原始 z 会被放大到几百，
     * 数值本身仍保留在原因里，但评分封顶保证排序可读
     */
    private static final double MAX_SCORE_Z = 10D;

    private final AssistantAgentTraceMapper traceMapper;
    private final Prd2ApiProperties properties;

    @Override
    public Scan scan(int days, int limit) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isAnomalyEnabled()) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "会话异常检测已关闭");
        }
        int windowDays = days <= 0 ? 7 : Math.min(days, 30);
        int cap = limit <= 0 ? DEFAULT_SCAN : Math.min(limit, MAX_SCAN);
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();

        List<AssistantAgentTrace> traces = traceMapper.selectList(new LambdaQueryWrapper<AssistantAgentTrace>()
                .eq(AssistantAgentTrace::getTenantId, tenantId)
                .eq(AssistantAgentTrace::getUserId, userId)
                .ge(AssistantAgentTrace::getCreateTime, LocalDateTime.now().minusDays(windowDays))
                .orderByDesc(AssistantAgentTrace::getId)
                .last("limit " + cap));

        Map<String, SessionAcc> sessionMap = new LinkedHashMap<>();
        for (AssistantAgentTrace trace : traces) {
            String sessionId = StrUtil.blankToDefault(trace.getSessionId(), "-");
            SessionAcc acc = sessionMap.computeIfAbsent(sessionId, key -> new SessionAcc());
            acc.steps++;
            if (!TOOL_STEPS.contains(StrUtil.blankToDefault(trace.getStepType(), ""))) {
                continue;
            }
            acc.toolSteps++;
            boolean success = trace.getSuccess() != null && trace.getSuccess() == 1;
            if (!success) {
                acc.failures++;
            }
            String tool = StrUtil.blankToDefault(trace.getToolName(), "-");
            acc.tools.add(tool);
            acc.callGroups.computeIfAbsent(tool + "|" + StrUtil.blankToDefault(trace.getToolArgs(), "{}"),
                    key -> new int[1])[0]++;
            long latency = trace.getLatencyMs() == null ? 0L : trace.getLatencyMs();
            acc.latencies.add(latency);
        }

        List<SessionAcc> sessions = sessionMap.values().stream().filter(acc -> acc.steps > 0).toList();
        double medianSteps = median(sessions.stream().map(acc -> (double) acc.steps).toList());
        double medianFailure = median(sessions.stream().map(SessionAcc::failureRate).toList());
        double medianDuplicate = median(sessions.stream().map(SessionAcc::duplicateRate).toList());
        double madSteps = mad(sessions.stream().map(acc -> (double) acc.steps).toList(), medianSteps);
        double madP95 = mad(sessions.stream().map(acc -> (double) acc.p95()).toList(), medianP95(sessions));
        boolean zEnabled = sessions.size() >= Math.max(1, config.getAnomalyMinSessions());
        double zThreshold = Math.max(1D, config.getAnomalyZThreshold());
        double medianP95 = medianP95(sessions);

        List<SessionAnomaly> anomalies = new ArrayList<>();
        for (Map.Entry<String, SessionAcc> entry : sessionMap.entrySet()) {
            SessionAcc acc = entry.getValue();
            List<String> reasons = new ArrayList<>();
            double score = 0D;

            if (acc.steps >= config.getMaxSteps()) {
                reasons.add("步数达到单轮上限 " + config.getMaxSteps() + "，存在死循环风险");
            }
            if (acc.failureRate() >= ABS_FAILURE_RATE) {
                reasons.add("失败率 " + percent(acc.failureRate()) + "% 超过绝对阈值 "
                        + percent(ABS_FAILURE_RATE) + "%");
            }
            if (acc.duplicateRate() >= ABS_DUPLICATE_RATE) {
                reasons.add("同参数重复调用占 " + percent(acc.duplicateRate()) + "%，超过绝对阈值 "
                        + percent(ABS_DUPLICATE_RATE) + "%");
            }
            if (zEnabled) {
                double zSteps = robustZ(acc.steps, medianSteps, madSteps);
                if (zSteps >= zThreshold) {
                    score = Math.max(score, Math.min(zSteps, MAX_SCORE_Z));
                    reasons.add("步数偏离基线：z=" + round1(zSteps) + "（中位数 " + (int) medianSteps + " 步）");
                }
                double zP95 = robustZ(acc.p95(), medianP95, madP95);
                if (zP95 >= zThreshold) {
                    score = Math.max(score, Math.min(zP95, MAX_SCORE_Z));
                    reasons.add("P95 延迟偏离基线：z=" + round1(zP95) + "（中位数 " + (long) medianP95 + "ms）");
                }
            }
            if (reasons.isEmpty()) {
                continue;
            }
            String severity = (acc.steps >= config.getMaxSteps() || acc.failureRate() >= HIGH_FAILURE_RATE)
                    ? "HIGH" : "MEDIUM";
            anomalies.add(new SessionAnomaly(entry.getKey(), acc.steps, acc.failures,
                    round2(acc.failureRate()), acc.duplicateSteps(), round2(acc.duplicateRate()),
                    acc.tools.size(), acc.p95(), round1(score), severity, reasons));
        }
        anomalies.sort(Comparator.comparingInt((SessionAnomaly a) -> "HIGH".equals(a.severity()) ? 0 : 1)
                .thenComparing(Comparator.comparingDouble(SessionAnomaly::score).reversed()));
        if (anomalies.size() > MAX_ANOMALIES) {
            anomalies = new ArrayList<>(anomalies.subList(0, MAX_ANOMALIES));
        }
        // 窗口内没有任何会话时不能报 NORMAL：这与“扫到了但一切正常”是两件不同的事
        String verdict = sessions.isEmpty() ? "NO_DATA"
                : anomalies.stream().anyMatch(a -> "HIGH".equals(a.severity())) ? "CRITICAL"
                : (anomalies.isEmpty() ? "NORMAL" : "SUSPECT");

        List<String> notes = new ArrayList<>();
        if (!zEnabled) {
            notes.add("会话数 " + sessions.size() + " 少于 " + config.getAnomalyMinSessions()
                    + "，仅使用绝对阈值判定，z 分数不生效");
        } else {
            notes.add("基于中位数 + MAD 稳健 z 分数（阈值 " + round1(zThreshold) + "），避免均值被离群值拉偏");
        }
        notes.add("失败率/重复率基线常为 0，z 分数会被零中位数放大，因此同时对这两项保留绝对阈值");

        log.info("会话异常扫描完成 sessions={} anomalies={} verdict={}",
                sessions.size(), anomalies.size(), verdict);
        return new Scan(windowDays, sessions.size(), anomalies.size(), verdict,
                round2(medianSteps), round2(medianFailure), round2(medianDuplicate), anomalies, notes);
    }

    private double medianP95(List<SessionAcc> sessions) {
        return median(sessions.stream().map(acc -> (double) acc.p95()).toList());
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0D;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(mid - 1) + sorted.get(mid)) / 2D : sorted.get(mid);
    }

    /**
     * 中位数绝对偏差；MAD 为 0 时用中位数的 10% 兜底，避免除零后把所有样本都判成异常
     */
    private double mad(List<Double> values, double median) {
        if (values.isEmpty()) {
            return 0D;
        }
        List<Double> deviations = values.stream().map(value -> Math.abs(value - median)).toList();
        return median(deviations);
    }

    private double robustZ(double value, double median, double mad) {
        double scale = mad > 0 ? mad : Math.max(Math.abs(median) * 0.1D, 1D);
        return 0.6745D * (value - median) / scale;
    }

    private double round1(double value) {
        return Math.round(value * 10D) / 10D;
    }

    private double round2(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private String percent(double rate) {
        return String.valueOf(Math.round(rate * 100D));
    }

    /** 单会话累计器 */
    private static final class SessionAcc {
        private int steps;
        private int toolSteps;
        private int failures;
        private final Set<String> tools = new java.util.LinkedHashSet<>();
        private final Map<String, int[]> callGroups = new LinkedHashMap<>();
        private final List<Long> latencies = new ArrayList<>();

        private double failureRate() {
            return toolSteps == 0 ? 0D : (double) failures / toolSteps;
        }

        private int duplicateSteps() {
            int duplicates = 0;
            for (int[] count : callGroups.values()) {
                if (count[0] > 1) {
                    duplicates += count[0] - 1;
                }
            }
            return duplicates;
        }

        private double duplicateRate() {
            return toolSteps == 0 ? 0D : (double) duplicateSteps() / toolSteps;
        }

        /** 单会话 P95，采用最近秩法 */
        private long p95() {
            if (latencies.isEmpty()) {
                return 0L;
            }
            List<Long> sorted = new ArrayList<>(latencies);
            sorted.sort(Comparator.naturalOrder());
            int index = (int) Math.ceil(0.95D * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }
    }
}
