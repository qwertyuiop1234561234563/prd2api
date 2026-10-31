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
import com.dbappsecurity.prd2api.service.AssistantDiagnoseService;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 失败归因诊断实现
 * <p>
 * 归因原则：只看“失败的步骤”，按步骤类型 + 结果文本做确定性分类，
 * 不依赖模型判断，保证同一条轨迹任何时候诊断结果一致（可回归、可对比）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantDiagnoseServiceImpl implements AssistantDiagnoseService {

    /** 工具类步骤：失败需要归因到具体工具 */
    private static final Set<String> TOOL_STEPS = Set.of("TOOL", "KNOWLEDGE");
    /** 需要人工确认的步骤 */
    private static final String CONFIRM_STEP = "CONFIRM";
    /** 预算 / 评审 / 循环控制类步骤 */
    private static final Set<String> CONTROL_STEPS = Set.of("BUDGET", "LOOP", "SUPERVISOR", "CRITIC", "SELF_CHECK");

    private static final int MAX_TRACE_SCAN = 200;

    /** 类别 → 中文标签 */
    private static final Map<String, String> LABELS = Map.of(
            "INPUT", "入参不合法",
            "TIMEOUT", "下游超时",
            "DOWNSTREAM", "下游不可用",
            "RETRIEVAL_GAP", "知识证据不足",
            "TOOL_ERROR", "工具业务错误",
            "CONFIRM_REJECTED", "人工确认被拒",
            "BUDGET_EXCEEDED", "预算超限",
            "LLM_QUALITY", "模型/编排质量",
            "CONTRACT_DEGRADED", "输出契约降级",
            "OTHER", "其他失败");

    /** 类别 → 修复建议 */
    private static final Map<String, String> SUGGESTIONS = Map.of(
            "INPUT", "检查模型生成的参数；前端补必填校验，必要时扩充工具描述与示例",
            "TIMEOUT", "评估调大工具超时或减少单次数据量；连续超时应降级为规则通道",
            "DOWNSTREAM", "检查下游依赖（向量库/数据库/外部接口）连通性与健康状态",
            "RETRIEVAL_GAP", "补充知识库语料或提升检索召回；答复应明确告知证据不足",
            "TOOL_ERROR", "核对工具实现的业务分支与返回文案，确认是否为预期失败",
            "CONFIRM_REJECTED", "用户主动拒绝，属正常交互；如需继续应重新发起确认",
            "BUDGET_EXCEEDED", "调大单轮预算或拆分任务；同时检查是否出现循环调用",
            "LLM_QUALITY", "检查模型可用性与配额；必要时降低单轮步数并加强自检",
            "CONTRACT_DEGRADED", "检查工具返回结构；证据不足型降级应同时排查知识库",
            "OTHER", "人工复核该步骤日志");

    private final AssistantAgentTraceMapper traceMapper;
    private final AssistantChatSessionMapper sessionMapper;
    private final MetricUtil metricUtil;
    private final Prd2ApiProperties properties;

    @Override
    public Report diagnose(String sessionId, int limit) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isDiagnoseEnabled()) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "失败归因诊断已关闭");
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

        List<Failure> failures = new ArrayList<>();
        long totalLatency = 0L;
        long slowest = 0L;
        for (AssistantAgentTrace trace : traces) {
            long latency = trace.getLatencyMs() == null ? 0L : trace.getLatencyMs();
            totalLatency += latency;
            slowest = Math.max(slowest, latency);
            if (trace.getSuccess() != null && trace.getSuccess() == 1) {
                continue;
            }
            String type = StrUtil.blankToDefault(trace.getStepType(), "");
            String tool = StrUtil.blankToDefault(trace.getToolName(), "");
            String reason = StrUtil.blankToDefault(trace.getToolResult(), "");
            String category = classify(type, tool, reason);
            failures.add(new Failure(trace.getStepNo() == null ? 0 : trace.getStepNo(), type, tool,
                    category, StrUtil.maxLength(reason, 160), latency));
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Failure failure : failures) {
            counts.merge(failure.category(), 1, Integer::sum);
        }
        List<Cause> causes = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String category = entry.getKey();
            int count = entry.getValue();
            causes.add(new Cause(category, LABELS.getOrDefault(category, category), count,
                    SUGGESTIONS.getOrDefault(category, "")));
        }
        causes.sort(Comparator.comparingInt(Cause::count).reversed());
        causes.forEach(cause -> metricUtil.agentDiagnose(cause.category(), cause.count()));

        String verdict = failures.isEmpty() ? "HEALTHY" : "FAILED";
        log.info("失败归因完成 session={} steps={} failed={} causes={}",
                sessionId, traces.size(), failures.size(), causes.size());
        return new Report(sessionId, verdict, traces.size(), failures.size(),
                totalLatency, slowest, causes, failures);
    }

    /**
     * 确定性归因：先按步骤类型分流，再按结果文本特征细分，顺序从“最可行动”到“兜底”
     */
    private String classify(String type, String tool, String reason) {
        if (CONFIRM_STEP.equals(type)) {
            return "CONFIRM_REJECTED";
        }
        if (type.startsWith("BUDGET")) {
            return "BUDGET_EXCEEDED";
        }
        if (CONTROL_STEPS.contains(type)) {
            return "LLM_QUALITY";
        }
        // 契约降级本身是兜底机制；若原因是“证据不足”，根因仍归到知识检索
        if ("CONTRACT".equals(type)) {
            return reason.contains("证据不足") || reason.contains("召回") || reason.contains("检索")
                    ? "RETRIEVAL_GAP" : "CONTRACT_DEGRADED";
        }
        if (!TOOL_STEPS.contains(type)) {
            return "OTHER";
        }
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
        if (reason.contains("知识库没有检索到") || reason.contains("证据不足")
                || reason.contains("召回")) {
            return "RETRIEVAL_GAP";
        }
        // 工具返回的业务性失败（找不到任务、页面不支持等）归到工具业务错误
        return StrUtil.isBlank(tool) ? "OTHER" : "TOOL_ERROR";
    }
}
