package com.dbappsecurity.prd2api.util;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer 埋点工具，适配 Prometheus + Grafana
 */
@Component
@RequiredArgsConstructor
public class MetricUtil {

    private static final String TASK_TOTAL = "prd2api_task_total";
    private static final String LLM_LATENCY = "prd2api_llm_latency";
    private static final String LLM_TOKENS = "prd2api_llm_tokens_total";
    private static final String RAG_LATENCY = "prd2api_rag_latency";

    private final MeterRegistry registry;

    public void taskCreated() {
        counter("created").increment();
    }

    public void taskSuccess() {
        counter("success").increment();
    }

    public void taskFail() {
        counter("fail").increment();
    }

    public void taskCanceled() {
        counter("canceled").increment();
    }

    private Counter counter(String status) {
        return Counter.builder(TASK_TOTAL)
                .description("PRD解析任务数量")
                .tag("status", status)
                .register(registry);
    }

    public void llmLatency(long millis) {
        Timer.builder(LLM_LATENCY)
                .description("LLM 调用耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    public void addTokens(long tokens) {
        if (tokens <= 0) {
            return;
        }
        Counter.builder(LLM_TOKENS)
                .description("LLM token 消耗总量")
                .register(registry)
                .increment(tokens);
    }

    public void ragLatency(long millis) {
        Timer.builder(RAG_LATENCY)
                .description("RAG 召回耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Agent 工具调用：按工具、风险等级与成败打点，并记录耗时
     */
    public void agentTool(String tool, String risk, boolean success, long millis) {
        Counter.builder("prd2api_agent_tool_total")
                .description("Agent 工具调用次数")
                .tag("tool", tool)
                .tag("risk", risk)
                .tag("status", success ? "success" : "fail")
                .register(registry)
                .increment();
        if (millis > 0) {
            Timer.builder("prd2api_agent_tool_latency")
                    .description("Agent 工具执行耗时")
                    .tag("tool", tool)
                    .publishPercentiles(0.5, 0.95)
                    .register(registry)
                    .record(millis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 危险操作人工确认（生成票据与确认执行都计数）
     */
    public void agentConfirm(String stage, boolean success) {
        Counter.builder("prd2api_agent_confirm_total")
                .description("Agent 危险操作确认次数")
                .tag("stage", stage)
                .tag("status", success ? "success" : "fail")
                .register(registry)
                .increment();
    }

    /**
     * 语义缓存命中/未命中
     */
    public void agentCache(boolean hit) {
        Counter.builder("prd2api_agent_semantic_cache_total")
                .description("语义缓存命中次数")
                .tag("result", hit ? "hit" : "miss")
                .register(registry)
                .increment();
    }

    /**
     * 上下文压缩节省的 token 数
     */
    public void agentContextCompressed(int savedTokens) {
        Counter.builder("prd2api_agent_context_compress_total")
                .description("上下文压缩次数")
                .register(registry)
                .increment();
        if (savedTokens > 0) {
            Counter.builder("prd2api_agent_context_saved_tokens_total")
                    .description("上下文压缩节省 token 总量")
                    .register(registry)
                    .increment(savedTokens);
        }
    }

    /**
     * 回答接地自检结果
     */
    public void agentSelfCheck(boolean grounded) {
        Counter.builder("prd2api_agent_self_check_total")
                .description("回答接地自检次数")
                .tag("result", grounded ? "grounded" : "ungrounded")
                .register(registry)
                .increment();
    }

    /**
     * 工具动态检索：是否裁剪、候选数与注入数，用于评估 prompt 成本
     */
    public void agentToolRoute(boolean filtered, int candidates, int selected) {
        Counter.builder("prd2api_agent_tool_route_total")
                .description("工具动态检索次数")
                .tag("filtered", filtered ? "true" : "false")
                .register(registry)
                .increment();
        DistributionSummary.builder("prd2api_agent_tool_route_candidates")
                .description("候选工具数")
                .tag("filtered", filtered ? "true" : "false")
                .register(registry)
                .record(candidates);
        DistributionSummary.builder("prd2api_agent_tool_route_selected")
                .description("注入工具数")
                .tag("filtered", filtered ? "true" : "false")
                .register(registry)
                .record(selected);
    }

    /**
     * 预算/死循环熔断次数
     */
    public void agentBudgetStop(String reason) {
        Counter.builder("prd2api_agent_budget_stop_total")
                .description("Agent 预算或死循环熔断次数")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /**
     * 子 Agent 委派
     */
    public void agentDelegate(String expert, boolean success) {
        Counter.builder("prd2api_agent_delegate_total")
                .description("子 Agent 委派次数")
                .tag("expert", expert)
                .tag("status", success ? "success" : "fail")
                .register(registry)
                .increment();
    }
}
