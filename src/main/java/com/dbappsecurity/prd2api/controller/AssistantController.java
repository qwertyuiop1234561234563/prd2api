package com.dbappsecurity.prd2api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.config.AsyncThreadPoolConfig;
import com.dbappsecurity.prd2api.dto.AssistantChatDTO;
import com.dbappsecurity.prd2api.dto.AssistantFeedbackDTO;
import com.dbappsecurity.prd2api.entity.AssistantUserMemory;
import com.dbappsecurity.prd2api.service.AssistantAgentService;
import com.dbappsecurity.prd2api.service.AssistantAuditService;
import com.dbappsecurity.prd2api.service.AssistantBulkheadService;
import com.dbappsecurity.prd2api.service.AssistantCancellationService;
import com.dbappsecurity.prd2api.service.AssistantDiagnoseService;
import com.dbappsecurity.prd2api.service.AssistantEfficiencyService;
import com.dbappsecurity.prd2api.service.AssistantEvalService;
import com.dbappsecurity.prd2api.service.AssistantFailureCaseService;
import com.dbappsecurity.prd2api.service.AssistantFeedbackService;
import com.dbappsecurity.prd2api.service.AssistantGoalDriftService;
import com.dbappsecurity.prd2api.service.AssistantHotCacheService;
import com.dbappsecurity.prd2api.service.AssistantMemoryGovernanceService;
import com.dbappsecurity.prd2api.service.AssistantMemoryService;
import com.dbappsecurity.prd2api.service.AssistantModelRoutingService;
import com.dbappsecurity.prd2api.service.AssistantPiiAuditService;
import com.dbappsecurity.prd2api.service.AssistantPlanValidatorService;
import com.dbappsecurity.prd2api.service.AssistantPlannerService;
import com.dbappsecurity.prd2api.service.AssistantPromptExperimentService;
import com.dbappsecurity.prd2api.service.AssistantReplayService;
import com.dbappsecurity.prd2api.service.AssistantService;
import com.dbappsecurity.prd2api.service.AssistantSessionAnomalyService;
import com.dbappsecurity.prd2api.service.AssistantToolCircuitService;
import com.dbappsecurity.prd2api.service.AssistantToolExampleService;
import com.dbappsecurity.prd2api.service.AssistantToolHealthService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.AgentStepVO;
import com.dbappsecurity.prd2api.vo.AssistantCapabilityVO;
import com.dbappsecurity.prd2api.vo.AssistantMemoryVO;
import com.dbappsecurity.prd2api.vo.AssistantMessageVO;
import com.dbappsecurity.prd2api.vo.AssistantReplyVO;
import com.dbappsecurity.prd2api.vo.AssistantSessionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 桌面助手接口：页面小人的对话框后端
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/assistant")
@Tag(name = "桌面助手", description = "页面小人问答：快捷查询、任务操作与平台答疑")
public class AssistantController {

    /**
     * SSE 连接最长保持时间，避免死连接长期占用线程。
     * 推理型模型（先输出思维链）单轮耗时可达 1~2 分钟，必须明显大于 Agent 单轮时限，
     * 否则正常生成会在中途被截断，前端拿不到 final 事件。
     */
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final AssistantService assistantService;
    private final AssistantMemoryService memoryService;
    private final AssistantEvalService evalService;
    private final AssistantCancellationService cancellationService;
    private final AssistantReplayService replayService;
    private final AssistantFailureCaseService failureCaseService;
    private final AssistantFeedbackService feedbackService;
    private final AssistantAuditService auditService;
    private final AssistantDiagnoseService diagnoseService;
    private final AssistantEfficiencyService efficiencyService;
    private final AssistantMemoryGovernanceService memoryGovernanceService;
    private final AssistantToolHealthService toolHealthService;
    private final AssistantToolExampleService toolExampleService;
    private final AssistantSessionAnomalyService sessionAnomalyService;
    private final AssistantToolCircuitService toolCircuitService;
    private final AssistantPlanValidatorService planValidatorService;
    private final AssistantModelRoutingService modelRoutingService;
    private final AssistantGoalDriftService goalDriftService;
    private final AssistantPromptExperimentService promptExperimentService;
    private final AssistantHotCacheService hotCacheService;
    private final AssistantBulkheadService bulkheadService;
    private final AssistantPiiAuditService piiAuditService;
    private final ThreadPoolTaskExecutor llmExecutor;

    public AssistantController(AssistantService assistantService,
                               AssistantMemoryService memoryService,
                               AssistantEvalService evalService,
                               AssistantCancellationService cancellationService,
                               AssistantReplayService replayService,
                               AssistantFailureCaseService failureCaseService,
                               AssistantFeedbackService feedbackService,
                               AssistantAuditService auditService,
                               AssistantDiagnoseService diagnoseService,
                               AssistantEfficiencyService efficiencyService,
                               AssistantMemoryGovernanceService memoryGovernanceService,
                               AssistantToolHealthService toolHealthService,
                               AssistantToolExampleService toolExampleService,
                               AssistantSessionAnomalyService sessionAnomalyService,
                               AssistantToolCircuitService toolCircuitService,
                               AssistantPlanValidatorService planValidatorService,
                               AssistantModelRoutingService modelRoutingService,
                               AssistantGoalDriftService goalDriftService,
                               AssistantPromptExperimentService promptExperimentService,
                               AssistantHotCacheService hotCacheService,
                               AssistantBulkheadService bulkheadService,
                               AssistantPiiAuditService piiAuditService,
                               @Qualifier(AsyncThreadPoolConfig.LLM_EXECUTOR) ThreadPoolTaskExecutor llmExecutor) {
        this.assistantService = assistantService;
        this.memoryService = memoryService;
        this.evalService = evalService;
        this.cancellationService = cancellationService;
        this.replayService = replayService;
        this.failureCaseService = failureCaseService;
        this.feedbackService = feedbackService;
        this.auditService = auditService;
        this.diagnoseService = diagnoseService;
        this.efficiencyService = efficiencyService;
        this.memoryGovernanceService = memoryGovernanceService;
        this.toolHealthService = toolHealthService;
        this.toolExampleService = toolExampleService;
        this.sessionAnomalyService = sessionAnomalyService;
        this.toolCircuitService = toolCircuitService;
        this.planValidatorService = planValidatorService;
        this.modelRoutingService = modelRoutingService;
        this.goalDriftService = goalDriftService;
        this.promptExperimentService = promptExperimentService;
        this.hotCacheService = hotCacheService;
        this.bulkheadService = bulkheadService;
        this.piiAuditService = piiAuditService;
        this.llmExecutor = llmExecutor;
    }

    @Operation(summary = "提问",
            description = "识别意图：任务/额度/文件/会员等操作类问题直接查询执行，其余走大模型，"
                    + "未配置模型时回落内置知识库；返回文本与前端动作（卡片或跳转）")
    @PostMapping("/chat")
    public RespModel<AssistantReplyVO> chat(@Valid @RequestBody AssistantChatDTO dto) {
        return RespModel.success(assistantService.chat(dto));
    }

    @Operation(summary = "流式提问（SSE）",
            description = "逐步推送 phase（模型思考中）与 step（工具执行完成/等待确认），最后推送 final 完整回复，"
                    + "前端可边执行边展示 Agent 过程")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AssistantChatDTO dto) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        // 请求线程的租户上下文不会自动带到线程池，先捕获再在新线程里恢复
        String userId = TenantContextUtil.getUserId();
        String tenantId = TenantContextUtil.getTenantId();
        String userKey = TenantContextUtil.getUserKey();
        String role = TenantContextUtil.getRole();
        llmExecutor.execute(() -> {
            TenantContextUtil.set(userId, tenantId, userKey, role);
            try {
                sendEvent(emitter, "phase", Map.of("phase", "START", "stepNo", 0));
                AssistantReplyVO vo = assistantService.chat(dto, new AssistantAgentService.AgentListener() {
                    @Override
                    public void onPhase(int stepNo, String phase) {
                        sendEvent(emitter, "phase", Map.of("phase", phase, "stepNo", stepNo));
                    }

                    @Override
                    public void onStep(AgentStepVO step) {
                        sendEvent(emitter, "step", step);
                    }
                });
                sendEvent(emitter, "final", vo);
                emitter.complete();
            } catch (Exception e) {
                log.warn("流式提问失败 msg={}", e.getMessage());
                sendEvent(emitter, "error", Map.of("message", StrUtil.maxLength(e.getMessage(), 200)));
                emitter.complete();
            } finally {
                TenantContextUtil.clear();
            }
        });
        return emitter;
    }

    @Operation(summary = "中断执行",
            description = "给正在运行的一轮 Agent 打中断标记，Agent 在下一个决策点停止调度工具并返回已完成结果；"
                    + "不传 sessionId 表示中断当前用户正在运行的一轮")
    @PostMapping("/cancel")
    public RespModel<Map<String, Object>> cancel(
            @Parameter(description = "会话ID，可选") @RequestParam(required = false) String sessionId) {
        cancellationService.request(sessionId);
        return RespModel.success(Map.of("canceled", true,
                "sessionId", StrUtil.blankToDefault(sessionId, "")));
    }

    @Operation(summary = "确认危险操作", description = "消费一次性确认票据后执行工具；票据过期或已被使用会直接拒绝")
    @PostMapping("/confirm/{confirmId}")
    public RespModel<AssistantReplyVO> confirm(
            @Parameter(description = "确认票据ID") @PathVariable String confirmId) {
        return RespModel.success(assistantService.confirm(confirmId));
    }

    @Operation(summary = "Agent 评测用例集", description = "返回黄金集，便于查看评测覆盖范围")
    @GetMapping("/eval/cases")
    public RespModel<List<com.dbappsecurity.prd2api.constant.AssistantEvalCaseConstant.EvalCase>> evalCases() {
        return RespModel.success(evalService.cases());
    }

    @Operation(summary = "运行 Agent 评测", description = "串行跑黄金集，输出通过率、工具选择准确率、token 与延迟")
    @PostMapping("/eval/run")
    public RespModel<AssistantEvalService.Report> runEval(
            @Parameter(description = "最多跑几条，默认全部")
            @RequestParam(defaultValue = "0") @Min(0) @Max(20) int limit) {
        return RespModel.success(evalService.run(limit));
    }

    @Operation(summary = "轨迹回放", description = "把会话里的只读步骤按原参数重跑，对比成败与结果相似度，检测行为漂移")
    @PostMapping("/replay/{sessionId}")
    public RespModel<AssistantReplayService.Report> replay(
            @Parameter(description = "会话ID") @PathVariable String sessionId,
            @Parameter(description = "最多回放步数，默认 20，最大 50")
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return RespModel.success(replayService.replay(sessionId, limit));
    }

    @Operation(summary = "挖掘失败样本", description = "从近 7 天失败轨迹聚簇出候选回归用例，并关联当时的用户问题")
    @PostMapping("/eval/mine")
    public RespModel<AssistantFailureCaseService.MineReport> mine(
            @Parameter(description = "最多挖掘多少组，默认 20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return RespModel.success(failureCaseService.mine(limit));
    }

    @Operation(summary = "候选回归用例", description = "查看已沉淀的失败样本用例")
    @GetMapping("/eval/candidates")
    public RespModel<List<AssistantFailureCaseService.Case>> candidates() {
        return RespModel.success(failureCaseService.candidates());
    }

    @Operation(summary = "重放候选用例", description = "用当前工具链重跑候选用例，输出已修复/仍存在")
    @PostMapping("/eval/candidates/run")
    public RespModel<AssistantFailureCaseService.RunReport> runCandidates() {
        return RespModel.success(failureCaseService.run());
    }

    @Operation(summary = "提交答复反馈", description = "点赞只统计；点踩会把该轮可重放的只读步骤自动转成回归用例")
    @PostMapping("/feedback")
    public RespModel<AssistantFeedbackService.Result> feedback(@Valid @RequestBody AssistantFeedbackDTO dto) {
        return RespModel.success(feedbackService.submit(dto));
    }

    @Operation(summary = "反馈统计", description = "赞踩比、点踩原因分布与已转回归用例数")
    @GetMapping("/feedback/stats")
    public RespModel<AssistantFeedbackService.Stats> feedbackStats() {
        return RespModel.success(feedbackService.stats());
    }

    @Operation(summary = "决策审计", description = "把轨迹聚合成一轮，校验「失败必反思 / 危险操作必确认 / 知识成功必有证据」并给出合规评分")
    @GetMapping("/audit/{sessionId}")
    public RespModel<AssistantAuditService.Report> audit(
            @Parameter(description = "会话ID") @PathVariable String sessionId,
            @Parameter(description = "最多扫描轨迹条数，默认 200")
            @RequestParam(defaultValue = "200") @Min(1) @Max(200) int limit) {
        return RespModel.success(auditService.audit(sessionId, limit));
    }

    @Operation(summary = "失败归因诊断",
            description = "把失败步骤按「入参 / 下游超时 / 下游不可用 / 知识证据不足 / 人工拒绝 / 预算 / 模型质量」归因，并给出修复建议")
    @GetMapping("/diagnose/{sessionId}")
    public RespModel<AssistantDiagnoseService.Report> diagnose(
            @Parameter(description = "会话ID") @PathVariable String sessionId,
            @Parameter(description = "最多扫描轨迹条数，默认 200")
            @RequestParam(defaultValue = "200") @Min(1) @Max(200) int limit) {
        return RespModel.success(diagnoseService.diagnose(sessionId, limit));
    }

    @Operation(summary = "执行效率画像",
            description = "按轨迹统计重复调用、同参数失败重试与延迟浪费，输出浪费占比与优化建议，用于压低单轮成本")
    @GetMapping("/efficiency/{sessionId}")
    public RespModel<AssistantEfficiencyService.Profile> efficiency(
            @Parameter(description = "会话ID") @PathVariable String sessionId,
            @Parameter(description = "最多扫描轨迹条数，默认 200")
            @RequestParam(defaultValue = "200") @Min(1) @Max(200) int limit) {
        return RespModel.success(efficiencyService.profile(sessionId, limit));
    }

    @Operation(summary = "记忆体检",
            description = "检测同一主题下互相矛盾的记忆、长期未更新的陈旧记忆与从未被召回的条目")
    @GetMapping("/memory/health")
    public RespModel<AssistantMemoryGovernanceService.Health> memoryHealth() {
        return RespModel.success(memoryGovernanceService.health());
    }

    @Operation(summary = "记忆压缩",
            description = "同一主题只保留较新的取值；apply=false 为演练（默认），apply=true 才会真正删除较旧条目")
    @PostMapping("/memory/compact")
    public RespModel<AssistantMemoryGovernanceService.Compaction> memoryCompact(
            @Parameter(description = "是否真正删除，false 只演练")
            @RequestParam(defaultValue = "false") boolean apply) {
        return RespModel.success(memoryGovernanceService.compact(apply));
    }

    @Operation(summary = "工具健康看板",
            description = "跨会话聚合近 N 天轨迹，按工具输出成功率、P95 延迟、失败原因分布，并按 SLO 阈值自动判级")
    @GetMapping("/tools/health")
    public RespModel<AssistantToolHealthService.Health> toolHealth(
            @Parameter(description = "统计窗口天数，默认 7，最大 90")
            @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days,
            @Parameter(description = "最多扫描轨迹条数，默认 2000，最大 5000")
            @RequestParam(defaultValue = "2000") @Min(1) @Max(5000) int limit) {
        return RespModel.success(toolHealthService.health(days, limit));
    }

    @Operation(summary = "工具调用样例沉淀",
            description = "从历史成功轨迹挖掘「工具 + 参数」样例并注入提示词，输出覆盖工具数、样例条数与注入字符数")
    @GetMapping("/tools/examples")
    public RespModel<AssistantToolExampleService.Report> toolExamples(
            @Parameter(description = "挖掘窗口天数，默认 30，最大 90")
            @RequestParam(defaultValue = "30") @Min(1) @Max(90) int days,
            @Parameter(description = "最多扫描轨迹条数，默认 3000，最大 5000")
            @RequestParam(defaultValue = "3000") @Min(1) @Max(5000) int limit) {
        return RespModel.success(toolExampleService.report(days, limit));
    }

    @Operation(summary = "会话异常检测",
            description = "按会话聚合步数、失败率、重复率与 P95 延迟，用中位数 + MAD 稳健 z 分数识别离群会话")
    @GetMapping("/anomaly/sessions")
    public RespModel<AssistantSessionAnomalyService.Scan> sessionAnomaly(
            @Parameter(description = "统计窗口天数，默认 7，最大 30")
            @RequestParam(defaultValue = "7") @Min(1) @Max(30) int days,
            @Parameter(description = "最多扫描轨迹条数，默认 2000，最大 5000")
            @RequestParam(defaultValue = "2000") @Min(1) @Max(5000) int limit) {
        return RespModel.success(sessionAnomalyService.scan(days, limit));
    }

    @Operation(summary = "计划可执行性校验",
            description = "对模型产出的执行计划做确定性校验：工具必须已注册、同一工具不重复、汇总步骤收尾，"
                    + "返回问题清单与自动修复后的计划；修不了的计划会被丢弃并回落 ReAct")
    @PostMapping("/plans/validate")
    public RespModel<AssistantPlanValidatorService.Repair> validatePlan(
            @RequestBody AssistantPlannerService.Plan plan) {
        return RespModel.success(planValidatorService.validate(plan));
    }

    @Operation(summary = "工具熔断状态",
            description = "基于最近真实调用轨迹判定各工具 CLOSED/OPEN/HALF_OPEN；执行器对 OPEN 工具快速失败，"
                    + "冷却后进入半开只放行一次探测")
    @GetMapping("/tools/circuit")
    public RespModel<AssistantToolCircuitService.Snapshot> toolCircuit(
            @Parameter(description = "是否绕过缓存重新统计，默认 false（缓存 30 秒）")
            @RequestParam(defaultValue = "false") boolean refresh) {
        return RespModel.success(toolCircuitService.snapshot(refresh));
    }

    @Operation(summary = "模型路由预览",
            description = "对问题做确定性复杂度打分，返回 SIMPLE/STANDARD/COMPLEX 档位、命中的加分因子与最终模型编码；"
                    + "不调用大模型，零 token，可用于上线前调阈值")
    @GetMapping("/routing/preview")
    public RespModel<AssistantModelRoutingService.Route> routingPreview(
            @Parameter(description = "待评估的问题") @RequestParam String message) {
        return RespModel.success(modelRoutingService.route(message));
    }

    @Operation(summary = "会话锁状态",
            description = "查询当前会话是否还有一轮对话在处理中，返回剩余锁有效期；前端可据此提示“上一轮仍在处理”")
    @GetMapping("/sessions/lock")
    public RespModel<AssistantService.LockState> sessionLock(
            @Parameter(description = "会话ID") @RequestParam String sessionId) {
        return RespModel.success(assistantService.lockState(sessionId));
    }

    @Operation(summary = "目标漂移评估",
            description = "对给定目标与已执行步骤做确定性关联度评估，返回聚焦比例与 FOCUSED/WEAK/DRIFTED/INSUFFICIENT；"
                    + "Agent 运行时仅在 DRIFTED 时注入一次纠偏提示，此接口用于离线调参与复盘；零 token")
    @PostMapping("/goal-drift/assess")
    public RespModel<AssistantGoalDriftService.Assessment> assessGoalDrift(
            @RequestBody GoalDriftRequest request) {
        return RespModel.success(goalDriftService.assess(request.goal(), request.steps()));
    }

    @Operation(summary = "提示词实验分流",
            description = "按用户标识做 SHA-256 稳定分桶，返回实验名、A/B 变体、桶号与提示词版本；"
                    + "同一用户重复请求结果恒定，不调用大模型")
    @GetMapping("/prompt/experiment")
    public RespModel<AssistantPromptExperimentService.Assignment> promptExperiment(
            @Parameter(description = "用户标识，缺省取当前登录用户")
            @RequestParam(required = false) String userKey) {
        String key = StrUtil.isBlank(userKey)
                ? StrUtil.blankToDefault(TenantContextUtil.getUserKey(), TenantContextUtil.getUserId())
                : userKey;
        return RespModel.success(promptExperimentService.assign(key));
    }

    @Operation(summary = "热点缓存状态",
            description = "只读工具结果的进程内一级缓存指标：L1 命中/未命中、单飞合并次数、实际回源次数与容量")
    @GetMapping("/concurrency/hot-cache")
    public RespModel<AssistantHotCacheService.Stats> hotCacheStats() {
        return RespModel.success(hotCacheService.stats());
    }

    @Operation(summary = "单飞并发自检",
            description = "N 个线程同时请求同一 key，验证缓存击穿保护：loaderCalls 理想值为 1，"
                    + "其余线程复用领先线程结果，用法可用于压测演示")
    @GetMapping("/concurrency/hot-cache/simulate")
    public RespModel<AssistantHotCacheService.Simulation> hotCacheSimulate(
            @Parameter(description = "自检标识，任意字符串") @RequestParam(defaultValue = "hot") String key,
            @Parameter(description = "并发线程数，1~100") @RequestParam(defaultValue = "50")
            @Min(1) @Max(100) int threads,
            @Parameter(description = "模拟下游耗时（毫秒），0~2000") @RequestParam(defaultValue = "300")
            @Min(0) @Max(2000) int delayMs) {
        return RespModel.success(hotCacheService.simulate(key, threads, delayMs));
    }

    @Operation(summary = "并发舱壁状态",
            description = "全局与租户维度的在途问答轮数、累计放行/拒绝次数，用于观测超载保护是否生效")
    @GetMapping("/concurrency/bulkhead")
    public RespModel<AssistantBulkheadService.Snapshot> bulkheadSnapshot() {
        return RespModel.success(bulkheadService.snapshot());
    }

    @Operation(summary = "舱壁并发自检",
            description = "N 个线程同时抢租户配额，验证租户级上限生效且快速失败；peakInFlight 应不超过租户上限")
    @GetMapping("/concurrency/bulkhead/simulate")
    public RespModel<AssistantBulkheadService.Simulation> bulkheadSimulate(
            @Parameter(description = "模拟租户标识") @RequestParam(defaultValue = "demo") String tenant,
            @Parameter(description = "并发线程数，1~100") @RequestParam(defaultValue = "12")
            @Min(1) @Max(100) int threads,
            @Parameter(description = "持有配额时长（毫秒），0~2000") @RequestParam(defaultValue = "150")
            @Min(0) @Max(2000) int holdMs,
            @Parameter(description = "获取配额等待毫秒数，0 表示快速失败") @RequestParam(defaultValue = "0")
            @Min(0) @Max(500) long waitMs) {
        return RespModel.success(bulkheadService.simulate(tenant, threads, holdMs, waitMs));
    }

    @Operation(summary = "PII 泄漏审计",
            description = "扫描历史会话消息与工具轨迹中的明文手机号/身份证/银行卡/邮箱，只返回打码预览，不改动数据")
    @GetMapping("/pii/audit")
    public RespModel<AssistantPiiAuditService.Report> piiAudit(
            @Parameter(description = "消息与轨迹各自最多扫描条数，默认 1000，最大 5000")
            @RequestParam(defaultValue = "1000") @Min(1) @Max(5000) int limit) {
        return RespModel.success(piiAuditService.audit(limit));
    }

    @Operation(summary = "PII 一键修复",
            description = "按审计同一套规则把命中的字段回写为打码文本，幂等可重复执行")
    @PostMapping("/pii/repair")
    public RespModel<AssistantPiiAuditService.Repair> piiRepair(
            @Parameter(description = "消息与轨迹各自最多扫描条数，默认 1000，最大 5000")
            @RequestParam(defaultValue = "1000") @Min(1) @Max(5000) int limit) {
        return RespModel.success(piiAuditService.repair(limit));
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 推送失败 name={} msg={}", name, e.getMessage());
        }
    }

    @Operation(summary = "会话历史", description = "按时间正序返回本人会话内的消息")
    @GetMapping("/session/{sessionId}/messages")
    public RespModel<List<AssistantMessageVO>> history(
            @Parameter(description = "会话ID") @PathVariable String sessionId,
            @Parameter(description = "返回条数，默认 20，最大 100")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return RespModel.success(assistantService.history(sessionId, limit));
    }

    @Operation(summary = "我的会话分页", description = "按最近活跃倒序")
    @GetMapping("/sessions")
    public RespModel<Page<AssistantSessionVO>> sessions(
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int pageSize) {
        return RespModel.success(assistantService.pageSessions(pageNum, pageSize));
    }

    @Operation(summary = "删除会话", description = "删除本人会话及其全部消息")
    @DeleteMapping("/session/{sessionId}")
    public RespModel<Void> deleteSession(@Parameter(description = "会话ID") @PathVariable String sessionId) {
        assistantService.deleteSession(sessionId);
        return RespModel.success();
    }

    @Operation(summary = "快捷提问", description = "弹窗初始展示的推荐问题")
    @GetMapping("/suggestions")
    public RespModel<List<String>> suggestions() {
        return RespModel.success(assistantService.suggestions());
    }

    @Operation(summary = "能力清单", description = "当前模式（AGENT/RULE）、已注册工具、长期记忆与知识库规模")
    @GetMapping("/capabilities")
    public RespModel<AssistantCapabilityVO> capabilities() {
        return RespModel.success(assistantService.capabilities());
    }

    @Operation(summary = "会话轨迹", description = "按时间正序返回该会话落库的 Agent 工具调用轨迹")
    @GetMapping("/session/{sessionId}/trace")
    public RespModel<List<AgentStepVO>> trace(
            @Parameter(description = "会话ID") @PathVariable String sessionId,
            @Parameter(description = "返回条数，默认 50，最大 200")
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return RespModel.success(assistantService.trace(sessionId, limit));
    }

    @Operation(summary = "我的长期记忆", description = "列出当前用户被记住的称呼、偏好与备注")
    @GetMapping("/memories")
    public RespModel<List<AssistantMemoryVO>> memories(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return RespModel.success(memoryService.list(limit).stream().map(this::toMemoryVO).toList());
    }

    @Operation(summary = "删除长期记忆", description = "只能删除本人的记忆")
    @DeleteMapping("/memory/{id}")
    public RespModel<Void> deleteMemory(@Parameter(description = "记忆ID") @PathVariable Long id) {
        memoryService.delete(id);
        return RespModel.success();
    }

    /**
     * 目标漂移评估入参：goal 为用户初始目标，steps 为已执行轨迹
     */
    public record GoalDriftRequest(String goal, List<AgentStepVO> steps) {
    }

    private AssistantMemoryVO toMemoryVO(AssistantUserMemory memory) {
        AssistantMemoryVO vo = new AssistantMemoryVO();
        vo.setId(memory.getId());
        vo.setKey(memory.getMemoryKey());
        vo.setValue(memory.getMemoryValue());
        vo.setSource(memory.getSource());
        vo.setUpdateTime(memory.getUpdateTime());
        return vo;
    }
}
