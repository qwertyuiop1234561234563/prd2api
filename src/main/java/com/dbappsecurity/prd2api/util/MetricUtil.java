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

    /**
     * 计划编排：生成、重规划与解析失败
     */
    public void agentPlan(String stage) {
        Counter.builder("prd2api_agent_plan_total")
                .description("Agent 计划编排次数")
                .tag("stage", stage)
                .register(registry)
                .increment();
    }

    /**
     * 用户主动中断执行
     */
    public void agentCancel() {
        Counter.builder("prd2api_agent_cancel_total")
                .description("Agent 用户中断次数")
                .register(registry)
                .increment();
    }

    /**
     * Supervisor 并行委派：并行墙钟耗时与相对串行节省的时间，用于量化编排收益
     */
    public void agentSupervisor(boolean parallel, long wallMs, long savedMs) {
        Counter.builder("prd2api_agent_supervisor_total")
                .description("Supervisor 委派批次")
                .tag("parallel", parallel ? "true" : "false")
                .register(registry)
                .increment();
        DistributionSummary.builder("prd2api_agent_supervisor_wall_ms")
                .description("Supervisor 委派墙钟耗时")
                .tag("parallel", parallel ? "true" : "false")
                .register(registry)
                .record(wallMs);
        DistributionSummary.builder("prd2api_agent_supervisor_saved_ms")
                .description("并行相对串行节省耗时")
                .register(registry)
                .record(Math.max(0L, savedMs));
    }

    /**
     * Actor-Critic 评审结果与评分分布
     */
    public void agentCritic(boolean passed, int score) {
        Counter.builder("prd2api_agent_critic_total")
                .description("Agent 答复评审次数")
                .tag("result", passed ? "passed" : "rejected")
                .register(registry)
                .increment();
        DistributionSummary.builder("prd2api_agent_critic_score")
                .description("答复评审得分")
                .tag("dimension", "overall")
                .register(registry)
                .record(score);
    }

    /**
     * Corrective RAG：改写次数、跳数与置信度，用于衡量检索修正是否真的奏效
     */
    public void agentRetrieval(boolean rewritten, int hops, double confidence, boolean sufficient) {
        Counter.builder("prd2api_agent_retrieval_total")
                .description("知识检索批次数")
                .tag("rewritten", rewritten ? "true" : "false")
                .tag("sufficient", sufficient ? "true" : "false")
                .register(registry)
                .increment();
        DistributionSummary.builder("prd2api_agent_retrieval_hops")
                .description("检索跳数")
                .register(registry)
                .record(Math.max(1, hops));
        DistributionSummary.builder("prd2api_agent_retrieval_confidence")
                .description("检索置信度")
                .register(registry)
                .record(Math.max(0.0D, Math.min(1.0D, confidence)));
    }

    /**
     * 记忆反思：从对话中沉淀稳定偏好的次数
     */
    public void agentMemoryReflection(int savedFacts) {
        Counter.builder("prd2api_agent_memory_reflection_total")
                .description("记忆反思批次数")
                .tag("result", savedFacts > 0 ? "saved" : "empty")
                .register(registry)
                .increment();
        if (savedFacts > 0) {
            DistributionSummary.builder("prd2api_agent_memory_reflection_facts")
                    .description("单轮反思写入的偏好条数")
                    .register(registry)
                    .record(savedFacts);
        }
    }

    /**
     * 断点续跑：保存/恢复/清除检查点，以及检查点内可复用步骤数
     */
    public void agentCheckpoint(String result, int steps) {
        Counter.builder("prd2api_agent_checkpoint_total")
                .description("Agent 断点续跑检查点操作次数")
                .tag("result", result)
                .register(registry)
                .increment();
        if (steps > 0) {
            DistributionSummary.builder("prd2api_agent_checkpoint_steps")
                    .description("检查点内已完成可复用步骤数")
                    .tag("result", result)
                    .register(registry)
                    .record(steps);
        }
    }

    /**
     * 工具选择经验策略：是否命中、推荐工具数
     */
    public void agentPolicy(boolean hit, int tools) {
        Counter.builder("prd2api_agent_policy_total")
                .description("工具选择经验策略次数")
                .tag("hit", hit ? "true" : "false")
                .register(registry)
                .increment();
        if (hit) {
            DistributionSummary.builder("prd2api_agent_policy_tools")
                    .description("经验策略推荐的工具数")
                    .register(registry)
                    .record(tools);
        }
    }

    /**
     * 失败反思：新教训 / 复用历史教训
     */
    public void agentReflection(boolean reused) {
        Counter.builder("prd2api_agent_reflection_total")
                .description("工具失败反思次数")
                .tag("result", reused ? "reused" : "new")
                .register(registry)
                .increment();
    }

    /**
     * PII 脱敏：本次脱敏命中的敏感信息条数
     */
    public void agentPiiMask(int hits) {
        if (hits <= 0) {
            return;
        }
        Counter.builder("prd2api_agent_pii_mask_total")
                .description("PII 脱敏命中次数")
                .register(registry)
                .increment(hits);
    }

    /**
     * 工具输出契约：通过 / 可修复 / 已降级
     */
    public void agentContract(String result) {
        Counter.builder("prd2api_agent_contract_total")
                .description("工具输出契约校验次数")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    /**
     * 答复溯源：被引用证据数、证据总数与引用覆盖率
     */
    public void agentProvenance(int verified, int total, double coverage) {
        Counter.builder("prd2api_agent_provenance_total")
                .description("答复溯源证据条数")
                .tag("result", verified > 0 ? "verified" : "none")
                .register(registry)
                .increment(Math.max(0, verified));
        if (total > 0) {
            Counter.builder("prd2api_agent_provenance_total")
                    .description("答复证据条数（未引用）")
                    .tag("result", "unverified")
                    .register(registry)
                    .increment(Math.max(0, total - verified));
            DistributionSummary.builder("prd2api_agent_provenance_coverage")
                    .description("答复引用覆盖率")
                    .register(registry)
                    .record(Math.max(0.0D, Math.min(1.0D, coverage)));
        }
    }

    /**
     * 轨迹回放：一致 / 漂移
     */
    public void agentReplay(String verdict) {
        Counter.builder("prd2api_agent_replay_total")
                .description("轨迹回放步骤数")
                .tag("verdict", verdict)
                .register(registry)
                .increment();
    }

    /**
     * 失败样本：挖掘 / 已修复 / 仍存在
     */
    public void agentFailureCase(String status, int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("prd2api_agent_failure_case_total")
                .description("失败样本回归用例数")
                .tag("status", status)
                .register(registry)
                .increment(count);
    }

    /**
     * 用户反馈：赞/踩与点踩原因
     */
    public void agentFeedback(String rating, String reason) {
        Counter.builder("prd2api_agent_feedback_total")
                .description("用户对助手答复的反馈次数")
                .tag("rating", rating)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /**
     * 点踩转回归用例：新建 / 重复 / 无可重放步骤
     */
    public void agentFeedbackCase(String result) {
        Counter.builder("prd2api_agent_feedback_case_total")
                .description("点踩转回归用例次数")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    /**
     * 决策审计：无违规 / 有违规
     */
    public void agentAudit(String result) {
        Counter.builder("prd2api_agent_audit_total")
                .description("Agent 决策审计次数")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    /**
     * 审计违规项，按规则区分
     */
    public void agentAuditViolation(String rule) {
        Counter.builder("prd2api_agent_audit_violation_total")
                .description("Agent 决策审计违规数")
                .tag("rule", rule)
                .register(registry)
                .increment();
    }

    /**
     * 工具入参安全守卫：放行 / 拒绝
     */
    public void agentArgGuard(boolean pass) {
        Counter.builder("prd2api_agent_arg_guard_total")
                .description("工具入参安全守卫次数")
                .tag("result", pass ? "pass" : "reject")
                .register(registry)
                .increment();
    }

    /**
     * 只读工具结果缓存：命中 / 未命中
     */
    public void agentToolCache(boolean hit) {
        Counter.builder("prd2api_agent_tool_cache_total")
                .description("只读工具结果缓存命中次数")
                .tag("result", hit ? "hit" : "miss")
                .register(registry)
                .increment();
    }

    /**
     * 工具级限流：放行 / 拒绝
     */
    public void agentToolRateLimit(boolean pass) {
        Counter.builder("prd2api_agent_tool_ratelimit_total")
                .description("工具级滑动窗口限流次数")
                .tag("result", pass ? "pass" : "reject")
                .register(registry)
                .increment();
    }

    /**
     * 失败归因：按根因类别累计失败步骤数
     */
    public void agentDiagnose(String category, int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("prd2api_agent_diagnose_total")
                .description("Agent 失败归因步骤数")
                .tag("category", category)
                .register(registry)
                .increment(count);
    }
}
