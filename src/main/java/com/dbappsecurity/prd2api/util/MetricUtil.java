package com.dbappsecurity.prd2api.util;

import io.micrometer.core.instrument.Counter;
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
}
