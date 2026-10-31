package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolExecutor;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolRegistry;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.AssistantActionType;
import com.dbappsecurity.prd2api.constant.AssistantIntentEnum;
import com.dbappsecurity.prd2api.constant.AssistantPromptConstant;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.dto.AssistantChatDTO;
import com.dbappsecurity.prd2api.entity.AssistantAgentTrace;
import com.dbappsecurity.prd2api.entity.AssistantChatMessage;
import com.dbappsecurity.prd2api.entity.AssistantChatSession;
import com.dbappsecurity.prd2api.mapper.AssistantAgentTraceMapper;
import com.dbappsecurity.prd2api.mapper.AssistantChatMessageMapper;
import com.dbappsecurity.prd2api.mapper.AssistantChatSessionMapper;
import com.dbappsecurity.prd2api.rag.KnowledgeBaseService;
import com.dbappsecurity.prd2api.service.AssistantAgentService;
import com.dbappsecurity.prd2api.service.AssistantBulkheadService;
import com.dbappsecurity.prd2api.service.AssistantCancellationService;
import com.dbappsecurity.prd2api.service.AssistantConfirmationService;
import com.dbappsecurity.prd2api.service.AssistantMemoryService;
import com.dbappsecurity.prd2api.service.AssistantProvenanceService;
import com.dbappsecurity.prd2api.service.AssistantSemanticCacheService;
import com.dbappsecurity.prd2api.service.AssistantService;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import com.dbappsecurity.prd2api.util.AssistantIntentResolver;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.PiiMaskUtil;
import com.dbappsecurity.prd2api.util.RateLimitUtil;
import com.dbappsecurity.prd2api.util.SensitiveFilterUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.AgentStepVO;
import com.dbappsecurity.prd2api.vo.AssistantCapabilityVO;
import com.dbappsecurity.prd2api.vo.AssistantCitationVO;
import com.dbappsecurity.prd2api.vo.AssistantMessageVO;
import com.dbappsecurity.prd2api.vo.AssistantReplyVO;
import com.dbappsecurity.prd2api.vo.AssistantSessionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 桌面助手服务实现
 * <p>
 * 设计取舍：系统操作类问题走确定性规则（快、零 token、无幻觉）；自由问答才调大模型，
 * 未配置密钥或调用失败时回落内置知识库，保证弹窗随时可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantServiceImpl implements AssistantService {

    private static final int MAX_PAGE_SIZE = 50;
    /** 释放会话锁：值仍属于自己才删除，避免误删别人重新获取的锁 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    /** 可作为答复证据的轨迹类型 */
    private static final List<String> CITATION_TYPES = List.of("KNOWLEDGE", "TOOL", "SUBAGENT", "MEMORY");
    /** 证据链条目上限，避免把整轮轨迹全量透出 */
    private static final int MAX_CITATIONS = 8;

    private final AssistantChatSessionMapper sessionMapper;
    private final AssistantChatMessageMapper messageMapper;
    private final AssistantAgentTraceMapper traceMapper;
    private final AssistantAgentService agentService;
    private final AssistantBulkheadService bulkheadService;
    private final AssistantMemoryService memoryService;
    private final AssistantProvenanceService provenanceService;
    private final AssistantToolRegistry toolRegistry;
    private final AssistantToolExecutor toolExecutor;
    private final AssistantConfirmationService confirmationService;
    private final AssistantSemanticCacheService semanticCacheService;
    private final LlmInvokeService llmInvokeService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AssistantIntentResolver intentResolver;
    private final SensitiveFilterUtil sensitiveFilterUtil;
    private final RateLimitUtil rateLimitUtil;
    private final MetricUtil metricUtil;
    private final Prd2ApiProperties properties;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public AssistantReplyVO chat(AssistantChatDTO dto) {
        return chat(dto, null);
    }

    @Override
    public AssistantReplyVO chat(AssistantChatDTO dto, AssistantAgentService.AgentListener listener) {
        Prd2ApiProperties.Assistant config = properties.getAssistant();
        if (config == null || !config.isEnabled()) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "智能助手暂未启用");
        }
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        String userKey = StrUtil.blankToDefault(TenantContextUtil.getUserKey(), userId);
        rateLimitUtil.check(RedisKeyConstant.limitAssistant(tenantId, userKey),
                config.getRateLimitPerMinute());

        // 并发舱壁：限制同时在执行的问答轮数，超限快速失败，避免瞬时流量打满线程池与下游依赖
        AssistantBulkheadService.Permit permit = bulkheadService.tryAcquire(tenantId);
        if (!permit.allowed()) {
            log.info("并发舱壁拒绝请求 tenant={} reason={}", tenantId, permit.reason());
            return overloadedReply(dto.getSessionId(), permit.reason());
        }

        // 会话互斥：同一会话同一时刻只允许一轮对话，避免并发请求互相覆盖历史、检查点与记忆
        String lockKey = null;
        String lockToken = null;
        try {
            if (config.isChatLockEnabled() && StrUtil.isNotBlank(dto.getSessionId())) {
                lockKey = RedisKeyConstant.assistantChatLock(tenantId, userKey, dto.getSessionId());
                lockToken = userId + ":" + UUID.randomUUID();
                try {
                    boolean acquired = Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(
                            lockKey, lockToken, Math.max(1, config.getChatLockSeconds()), TimeUnit.SECONDS));
                    if (!acquired) {
                        log.info("会话正在处理中，拒绝并发请求 sessionId={}", dto.getSessionId());
                        return busyReply(dto.getSessionId());
                    }
                } catch (Exception e) {
                    // 锁服务异常不能阻断对话，降级为不互斥
                    log.warn("会话锁获取失败，本轮降级为并发执行 msg={}", e.getMessage());
                    lockToken = null;
                }
            }
        String message = sensitiveFilterUtil.mask(dto.getMessage()).trim();
        AssistantChatSession session = loadOrCreateSession(dto.getSessionId(), tenantId, userId, message);
        List<AssistantChatMessage> history = loadRecentMessages(session.getSessionId(), config.getHistoryLimit());

        AssistantChatMessage userMessage = new AssistantChatMessage();
        userMessage.setSessionId(session.getSessionId());
        userMessage.setTenantId(tenantId);
        userMessage.setUserId(userId);
        userMessage.setRole(AssistantChatMessage.ROLE_USER);
        userMessage.setContent(maskPii(message));
        userMessage.setCreateTime(LocalDateTime.now());
        messageMapper.insert(userMessage);

        AssistantIntentEnum intent = intentResolver.resolve(message);
        // 语义缓存只对无个人数据、无动作的知识型问答生效，命中直接复用，省掉一轮模型调用
        AssistantSemanticCacheService.Entry cached = semanticCacheService.lookup(message);
        AssistantAgentService.AgentOutcome outcome = cached != null
                ? cacheOutcome(cached, listener)
                : agentService.run(session.getSessionId(), message, history, listener);
        String reply = maskPii(outcome.reply());
        // 轨迹要返回前端并落库：统一脱敏，避免 MEMORY/兜底等未走 SSE 的步骤漏出 PII
        maskSteps(outcome.steps());
        // 答复溯源：把证据与答复做原文对齐，量化“结论有据可依”的比例
        AssistantProvenanceService.Report provenance = provenanceReport(reply, outcome.steps());
        boolean degraded = outcome.degraded();
        if (cached == null && semanticCacheService.cacheable(message, outcome, intent.getCode())) {
            semanticCacheService.put(message, reply, outcome.mode(), intent.getCode(), outcome.steps());
        }
        AssistantReplyVO.Action action = buildAction(outcome.actionType(), outcome.actionLabel(),
                outcome.actionTarget(), outcome.actionData());

        AssistantChatMessage assistantMessage = new AssistantChatMessage();
        assistantMessage.setSessionId(session.getSessionId());
        assistantMessage.setTenantId(tenantId);
        assistantMessage.setUserId(userId);
        assistantMessage.setRole(AssistantChatMessage.ROLE_ASSISTANT);
        assistantMessage.setContent(reply);
        assistantMessage.setIntent(intent.getCode());
        assistantMessage.setCreateTime(LocalDateTime.now());
        messageMapper.insert(assistantMessage);

        sessionMapper.update(null, new LambdaUpdateWrapper<AssistantChatSession>()
                .eq(AssistantChatSession::getSessionId, session.getSessionId())
                .setSql("message_count = message_count + 2")
                .set(AssistantChatSession::getUpdateTime, LocalDateTime.now()));

        persistTrace(session.getSessionId(), tenantId, userId, outcome.steps());

        AssistantReplyVO vo = new AssistantReplyVO();
        vo.setSessionId(session.getSessionId());
        vo.setMessageId(assistantMessage.getId());
        vo.setReply(reply);
        vo.setIntent(intent.getCode());
        vo.setIntentLabel(intent.getLabel());
        vo.setAction(action);
        vo.setSuggestions(suggestionsFor(intent));
        vo.setDegraded(degraded);
        vo.setMode(outcome.mode());
        vo.setTokenUsed(outcome.tokenUsed());
        vo.setSteps(outcome.steps());
        vo.setCacheHit(cached != null);
        vo.setCacheScore(cached == null ? 0.0D : cached.score());
        vo.setGrounded(grounded(outcome.steps()));
        vo.setGroundingNote(groundingNote(outcome.steps()));
        vo.setCitations(citations(outcome.steps(), provenance));
        vo.setCitationCoverage(provenance.coverage());
        vo.setCitationVerified(provenance.verified());
        vo.setCitationTotal(provenance.total());
        vo.setTime(assistantMessage.getCreateTime());
        return vo;
        } finally {
            if (lockKey != null && lockToken != null) {
                try {
                    stringRedisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockToken);
                } catch (Exception e) {
                    log.debug("会话锁释放失败 msg={}", e.getMessage());
                }
            }
            bulkheadService.release(permit);
        }
    }

    /**
     * 并发舱壁拒绝时的快速返回：不落库、不调用模型，避免超载时延迟进一步恶化
     */
    private AssistantReplyVO overloadedReply(String sessionId, String reason) {
        AssistantReplyVO vo = new AssistantReplyVO();
        vo.setSessionId(sessionId);
        vo.setReply("当前并发较高（" + reason + "），请稍后再试。");
        vo.setMode("OVERLOADED");
        vo.setDegraded(true);
        vo.setSteps(List.of());
        vo.setSuggestions(List.of());
        vo.setTime(LocalDateTime.now());
        return vo;
    }

    /**
     * 会话被占用时的快速返回：不落库、不调用模型，避免并发把同一轮问答写两遍
     */
    private AssistantReplyVO busyReply(String sessionId) {
        AssistantReplyVO vo = new AssistantReplyVO();
        vo.setSessionId(sessionId);
        vo.setReply("上一条消息还在处理中，请稍后再试。");
        vo.setMode("LOCKED");
        vo.setDegraded(true);
        vo.setSteps(List.of());
        vo.setSuggestions(List.of());
        vo.setTime(LocalDateTime.now());
        return vo;
    }

    /**
     * 语义缓存命中：把缓存答复包装成一次“零 token”的 Agent 结果，并补一条缓存轨迹
     */
    private AssistantAgentService.AgentOutcome cacheOutcome(AssistantSemanticCacheService.Entry cached,
                                                            AssistantAgentService.AgentListener listener) {
        List<AgentStepVO> steps = new ArrayList<>(
                cached.steps() == null ? List.of() : cached.steps());
        AgentStepVO step = new AgentStepVO();
        step.setStepNo(steps.size() + 1);
        step.setType("CACHE");
        step.setToolName("semantic_cache");
        step.setTitle("语义缓存命中");
        step.setArguments("命中相似历史问题");
        step.setResult(StrUtil.maxLength(String.format(
                "余弦相似度 %.4f，直接复用历史答复，本轮未调用大模型", cached.score()), 300));
        step.setSuccess(true);
        step.setTime(LocalDateTime.now());
        steps.add(step);
        if (listener != null) {
            listener.onStep(step);
        }
        return new AssistantAgentService.AgentOutcome(cached.reply(),
                StrUtil.blankToDefault(cached.mode(), "LLM"), false, steps,
                AssistantActionType.NONE, null, null, null, 0L, List.of());
    }

    /**
     * 答复是否通过接地自检（没有自检步骤时视为未发现问题的正常答复）
     */
    private boolean grounded(List<AgentStepVO> steps) {
        return steps == null || steps.stream()
                .noneMatch(step -> "SELF_CHECK".equals(step.getType()) && !step.isSuccess());
    }

    private String groundingNote(List<AgentStepVO> steps) {
        if (steps == null) {
            return null;
        }
        return steps.stream()
                .filter(step -> "SELF_CHECK".equals(step.getType()) && !step.isSuccess())
                .map(AgentStepVO::getResult)
                .findFirst()
                .orElse(null);
    }

    /**
     * 证据链：把知识来源、系统数据、子 Agent 结论与长期记忆转成可回溯条目
     */
    private List<AssistantCitationVO> citations(List<AgentStepVO> steps,
                                                AssistantProvenanceService.Report provenance) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        Map<Integer, AssistantProvenanceService.Anchor> anchors = provenance == null
                ? Map.of()
                : provenance.anchors().stream()
                .collect(Collectors.toMap(AssistantProvenanceService.Anchor::stepNo, anchor -> anchor,
                        (left, right) -> left));
        List<AssistantCitationVO> result = new ArrayList<>();
        for (AgentStepVO step : steps) {
            if (result.size() >= MAX_CITATIONS) {
                break;
            }
            if (!step.isSuccess() || !CITATION_TYPES.contains(StrUtil.blankToDefault(step.getType(), ""))) {
                continue;
            }
            AssistantCitationVO citation = new AssistantCitationVO();
            citation.setStepNo(step.getStepNo());
            citation.setType(step.getType());
            citation.setTitle(StrUtil.blankToDefault(step.getTitle(), step.getToolName()));
            citation.setSource(StrUtil.blankToDefault(step.getSource(), defaultSource(step.getType())));
            citation.setSnippet(StrUtil.maxLength(step.getResult(), 160));
            citation.setTime(step.getTime());
            AssistantProvenanceService.Anchor anchor = anchors.get(step.getStepNo());
            if (anchor != null) {
                citation.setChunkId(anchor.chunkId());
                citation.setReplyStart(anchor.replyStart());
                citation.setReplyEnd(anchor.replyEnd());
                citation.setOverlap(anchor.overlap());
                citation.setVerified(anchor.verified());
            }
            result.add(citation);
        }
        return result;
    }

    /**
     * 答复溯源：开关关闭或计算异常时返回空报告，不影响主流程
     */
    private AssistantProvenanceService.Report provenanceReport(String reply, List<AgentStepVO> steps) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isProvenanceEnabled()) {
            return new AssistantProvenanceService.Report(0.0D, 0, 0, List.of());
        }
        try {
            return provenanceService.trace(reply, steps);
        } catch (Exception e) {
            log.warn("答复溯源计算失败 msg={}", e.getMessage());
            return new AssistantProvenanceService.Report(0.0D, 0, 0, List.of());
        }
    }

    private String defaultSource(String type) {
        return switch (StrUtil.blankToDefault(type, "")) {
            case "KNOWLEDGE" -> "平台知识库";
            case "SUBAGENT" -> "专家子 Agent";
            case "MEMORY" -> "用户长期记忆";
            default -> "系统实时数据";
        };
    }

    @Override
    public List<AssistantMessageVO> history(String sessionId, int limit) {
        AssistantChatSession session = requireOwnedSession(sessionId);
        int size = limit <= 0 ? 20 : Math.min(limit, 100);
        List<AssistantChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<AssistantChatMessage>()
                        .eq(AssistantChatMessage::getSessionId, session.getSessionId())
                        .orderByDesc(AssistantChatMessage::getId)
                        .last("limit " + size));
        Collections.reverse(messages);
        List<AssistantMessageVO> result = new ArrayList<>(messages.size());
        for (AssistantChatMessage message : messages) {
            AssistantMessageVO vo = new AssistantMessageVO();
            vo.setId(message.getId());
            vo.setRole(message.getRole());
            vo.setContent(message.getContent());
            vo.setIntent(message.getIntent());
            vo.setCreateTime(message.getCreateTime());
            result.add(vo);
        }
        return result;
    }

    @Override
    public Page<AssistantSessionVO> pageSessions(int pageNum, int pageSize) {
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        Page<AssistantChatSession> page = sessionMapper.selectPage(
                new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE)),
                new LambdaQueryWrapper<AssistantChatSession>()
                        .eq(AssistantChatSession::getTenantId, tenantId)
                        .eq(AssistantChatSession::getUserId, userId)
                        .orderByDesc(AssistantChatSession::getUpdateTime));
        Page<AssistantSessionVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(session -> {
            AssistantSessionVO vo = new AssistantSessionVO();
            vo.setSessionId(session.getSessionId());
            vo.setTitle(session.getTitle());
            vo.setMessageCount(session.getMessageCount());
            vo.setUpdateTime(session.getUpdateTime());
            return vo;
        }).toList());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(String sessionId) {
        AssistantChatSession session = requireOwnedSession(sessionId);
        messageMapper.delete(new LambdaQueryWrapper<AssistantChatMessage>()
                .eq(AssistantChatMessage::getSessionId, session.getSessionId()));
        sessionMapper.deleteById(session.getSessionId());
    }

    @Override
    public List<String> suggestions() {
        return AssistantPromptConstant.SUGGESTIONS;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cleanExpired() {
        Prd2ApiProperties.Assistant config = properties.getAssistant();
        int days = config == null || config.getSessionExpireDays() <= 0 ? 30 : config.getSessionExpireDays();
        LocalDateTime threshold = LocalDateTime.now().minusDays(days);
        int messages = messageMapper.delete(new LambdaQueryWrapper<AssistantChatMessage>()
                .lt(AssistantChatMessage::getCreateTime, threshold));
        int sessions = sessionMapper.delete(new LambdaQueryWrapper<AssistantChatSession>()
                .lt(AssistantChatSession::getUpdateTime, threshold));
        if (messages + sessions > 0) {
            log.info("助手会话清理完成 sessions={} messages={} threshold={}", sessions, messages, threshold);
        }
        return messages + sessions;
    }

    @Override
    public LockState lockState(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return new LockState(false, -1L);
        }
        String tenantId = TenantContextUtil.requireTenantId();
        String userKey = TenantContextUtil.getUserKey();
        if (StrUtil.isBlank(userKey)) {
            userKey = TenantContextUtil.getUserId();
        }
        if (StrUtil.isBlank(userKey)) {
            return new LockState(false, -1L);
        }
        String key = RedisKeyConstant.assistantChatLock(tenantId, userKey, sessionId);
        try {
            Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (ttl != null && ttl > 0) {
                return new LockState(true, ttl);
            }
        } catch (Exception e) {
            log.debug("会话锁状态查询失败 msg={}", e.getMessage());
        }
        return new LockState(false, -1L);
    }

    /**
     * 会话必须属于当前租户与当前登录用户，避免越权读取他人对话
     */
    private AssistantChatSession requireOwnedSession(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "sessionId 不能为空");
        }
        AssistantChatSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AssistantChatSession>()
                .eq(AssistantChatSession::getSessionId, sessionId)
                .eq(AssistantChatSession::getTenantId, TenantContextUtil.requireTenantId())
                .eq(AssistantChatSession::getUserId, TenantContextUtil.requireUserId()));
        if (session == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "会话不存在或已过期，请重新发起对话");
        }
        return session;
    }

    private AssistantChatSession loadOrCreateSession(String sessionId, String tenantId,
                                                     String userId, String message) {
        if (StrUtil.isNotBlank(sessionId)) {
            return requireOwnedSession(sessionId);
        }
        LocalDateTime now = LocalDateTime.now();
        AssistantChatSession session = new AssistantChatSession();
        session.setSessionId(IdUtil.fastSimpleUUID());
        session.setTenantId(tenantId);
        session.setUserId(userId);
        session.setTitle(StrUtil.maxLength(message, 30));
        session.setMessageCount(0);
        session.setCreateTime(now);
        session.setUpdateTime(now);
        session.setDeleted(0);
        sessionMapper.insert(session);
        return session;
    }

    /**
     * 取最近若干条消息作为多轮上下文，倒序查询后反转，按时间正序拼接
     */
    private List<AssistantChatMessage> loadRecentMessages(String sessionId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<AssistantChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<AssistantChatMessage>()
                        .eq(AssistantChatMessage::getSessionId, sessionId)
                        .orderByDesc(AssistantChatMessage::getId)
                        .last("limit " + Math.min(limit, 20)));
        Collections.reverse(messages);
        return messages;
    }

    private AssistantReplyVO.Action buildAction(String type, String label, String target, Object data) {
        AssistantReplyVO.Action action = new AssistantReplyVO.Action();
        action.setType(StrUtil.blankToDefault(type, AssistantActionType.NONE));
        action.setLabel(label);
        action.setTarget(target);
        action.setData(data);
        return action;
    }

    /**
     * 按意图给出下一步推荐，引导用户继续对话
     */
    private List<String> suggestionsFor(AssistantIntentEnum intent) {
        return switch (intent) {
            case TASK_LIST, TASK_STATUS, CANCEL_TASK, RETRY_TASK ->
                    List.of("我的额度还剩多少？", "报告怎么导出？", "VIP 有哪些权益？");
            case QUOTA, VIP -> List.of("VIP 有哪些权益？", "怎么上传 PRD？", "你能做什么？");
            case FILE_LIST -> List.of("我的任务", "怎么上传 PRD？", "报告怎么导出？");
            case ADMIN_STATS -> List.of("我的任务", "我的额度", "你能做什么？");
            default -> AssistantPromptConstant.SUGGESTIONS.subList(0, 3);
        };
    }

    @Override
    public AssistantCapabilityVO capabilities() {
        Prd2ApiProperties.Agent config = properties.getAgent();
        boolean enabled = config != null && config.isEnabled();
        boolean toolCalling = enabled && llmInvokeService.toolCallingAvailable();
        AssistantCapabilityVO vo = new AssistantCapabilityVO();
        vo.setAgentEnabled(enabled);
        vo.setLlmAvailable(llmInvokeService.available());
        vo.setToolCalling(toolCalling);
        vo.setMode(toolCalling ? "AGENT" : "RULE");
        vo.setMaxSteps(config == null ? 0 : config.getMaxSteps());
        vo.setTools(toolRegistry.listTools().stream()
                .map(tool -> new AssistantCapabilityVO.ToolItem(tool.name(), tool.title(), tool.risk()))
                .toList());
        vo.setToolCount(vo.getTools().size());
        vo.setMemoryCount(memoryService.count());
        vo.setKnowledgeDocs(knowledgeBaseService.docCount());
        vo.setKnowledgeVectorEnabled(knowledgeBaseService.vectorEnabled());
        if (config != null) {
            vo.setContextTokenBudget(config.getContextTokenBudget());
            vo.setSelfCheckEnabled(config.isSelfCheck());
            vo.setSemanticCacheEnabled(config.isSemanticCache());
            vo.setSemanticCacheThreshold(config.getSemanticCacheThreshold());
            vo.setToolRoutingEnabled(config.isToolRouting());
            vo.setToolTopK(config.getToolTopK());
            vo.setRunTimeoutMs(config.getRunTimeoutMs());
            vo.setMaxTokenBudget(config.getMaxTokenBudget());
            vo.setDelegateEnabled(config.isDelegateEnabled());
            vo.setExperts(toolRegistry.experts().stream().sorted().toList());
            vo.setPlanEnabled(config.isPlanEnabled());
            vo.setPlanMaxSteps(config.getPlanMaxSteps());
            vo.setCancelEnabled(true);
            vo.setSupervisorEnabled(config.isDelegateEnabled());
            vo.setCriticEnabled(config.isCriticEnabled());
            vo.setCriticMinScore(config.getCriticMinScore());
            vo.setCorrectiveRagEnabled(config.isCorrectiveRag());
            vo.setRetrievalMinConfidence(config.getRetrievalMinConfidence());
            vo.setMemoryReflectionEnabled(config.isMemoryReflection());
            vo.setCheckpointEnabled(config.isCheckpointEnabled());
            vo.setToolPolicyEnabled(config.isToolPolicyEnabled());
            vo.setReflectionEnabled(config.isReflectionEnabled());
            vo.setPiiMaskEnabled(config.isPiiMaskEnabled());
            vo.setContractEnabled(config.isContractEnabled());
            vo.setProvenanceEnabled(config.isProvenanceEnabled());
            vo.setReplayEnabled(config.isReplayEnabled());
            vo.setFailureMiningEnabled(config.isFailureMiningEnabled());
            vo.setFeedbackEnabled(config.isFeedbackEnabled());
            vo.setAuditEnabled(config.isAuditEnabled());
            vo.setToolCacheEnabled(config.isToolCacheEnabled());
            vo.setToolCacheTtlSeconds(config.getToolCacheTtlSeconds());
            vo.setArgumentGuardEnabled(config.isArgumentGuardEnabled());
            vo.setToolRateLimitPerMinute(config.getToolRateLimitPerMinute());
            vo.setDiagnoseEnabled(config.isDiagnoseEnabled());
        }
        return vo;
    }

    @Override
    public List<AgentStepVO> trace(String sessionId, int limit) {
        AssistantChatSession session = requireOwnedSession(sessionId);
        int size = limit <= 0 ? 50 : Math.min(limit, 200);
        List<AssistantAgentTrace> traces = traceMapper.selectList(
                new LambdaQueryWrapper<AssistantAgentTrace>()
                        .eq(AssistantAgentTrace::getSessionId, session.getSessionId())
                        .orderByDesc(AssistantAgentTrace::getId)
                        .last("limit " + size));
        Collections.reverse(traces);
        List<AgentStepVO> result = new ArrayList<>(traces.size());
        for (AssistantAgentTrace trace : traces) {
            AgentStepVO step = new AgentStepVO();
            step.setStepNo(trace.getStepNo() == null ? 0 : trace.getStepNo());
            step.setType(trace.getStepType());
            step.setToolName(trace.getToolName());
            step.setTitle(toolRegistry.title(trace.getToolName()));
            step.setArguments(trace.getToolArgs());
            step.setResult(trace.getToolResult());
            step.setLatencyMs(trace.getLatencyMs() == null ? 0L : trace.getLatencyMs());
            step.setSuccess(trace.getSuccess() != null && trace.getSuccess() == 1);
            step.setTime(trace.getCreateTime());
            result.add(step);
        }
        return result;
    }

    /**
     * 危险操作确认：票据一次性消费，消费成功才真正执行工具，失败不修改系统状态
     */
    @Override
    public AssistantReplyVO confirm(String confirmId) {
        AssistantConfirmationService.PendingConfirm ticket = confirmationService.consume(confirmId);
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        if (!toolRegistry.supports(ticket.toolName())) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "该工具不在白名单内，禁止执行");
        }
        long start = System.currentTimeMillis();
        List<AssistantToolExecutor.Execution> executions = toolExecutor.executeAll(
                List.of(new LlmInvokeService.ToolCall(ticket.confirmId(), ticket.toolName(), ticket.arguments())));
        AssistantToolExecutor.Execution execution = executions.get(0);
        AssistantToolRegistry.ToolResult result = execution.result();
        metricUtil.agentConfirm("execute", result.success());
        long latency = Math.max(execution.latencyMs(), System.currentTimeMillis() - start);

        AgentStepVO step = new AgentStepVO();
        step.setStepNo(1);
        step.setType("TOOL");
        step.setToolName(ticket.toolName());
        step.setTitle(toolRegistry.title(ticket.toolName()) + "（已确认）");
        step.setArguments(StrUtil.maxLength(ticket.arguments(), 500));
        step.setResult(StrUtil.maxLength(result.summary(), 2000));
        step.setLatencyMs(latency);
        step.setSuccess(result.success());
        step.setTime(LocalDateTime.now());

        String reply = result.success()
                ? "已按你的确认执行：" + result.summary()
                : "执行未成功：" + StrUtil.blankToDefault(result.summary(), "请稍后重试");
        reply = maskPii(reply);
        persistTrace(ticket.sessionId(), tenantId, userId, List.of(step));
        appendAssistantMessage(ticket.sessionId(), tenantId, userId, reply);

        AssistantReplyVO vo = new AssistantReplyVO();
        vo.setSessionId(ticket.sessionId());
        vo.setReply(reply);
        vo.setIntent("CONFIRM");
        vo.setIntentLabel("危险操作确认");
        vo.setDegraded(false);
        vo.setMode("AGENT");
        vo.setTokenUsed(0L);
        vo.setSteps(List.of(step));
        vo.setSuggestions(AssistantPromptConstant.SUGGESTIONS.subList(0, 3));
        vo.setCitations(citations(List.of(step), null));
        vo.setTime(LocalDateTime.now());
        if (result.success() && StrUtil.isNotBlank(result.actionType())
                && !AssistantActionType.NONE.equals(result.actionType())) {
            vo.setAction(buildAction(result.actionType(), result.actionLabel(),
                    result.actionTarget(), result.data()));
        }
        return vo;
    }

    /**
     * PII 脱敏统一入口：开关关闭时原样返回
     */
    private String maskPii(String text) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isPiiMaskEnabled() || StrUtil.isBlank(text)) {
            return text;
        }
        PiiMaskUtil.MaskedText masked = PiiMaskUtil.maskAndCount(text);
        metricUtil.agentPiiMask(masked.total());
        return masked.text();
    }

    private void maskSteps(List<AgentStepVO> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        for (AgentStepVO step : steps) {
            step.setTitle(maskPii(step.getTitle()));
            step.setArguments(maskPii(step.getArguments()));
            step.setResult(maskPii(step.getResult()));
            step.setSource(maskPii(step.getSource()));
        }
    }

    private void appendAssistantMessage(String sessionId, String tenantId, String userId, String content) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }
        try {
            AssistantChatMessage message = new AssistantChatMessage();
            message.setSessionId(sessionId);
            message.setTenantId(tenantId);
            message.setUserId(userId);
            message.setRole(AssistantChatMessage.ROLE_ASSISTANT);
            message.setContent(content);
            message.setIntent("CONFIRM");
            message.setCreateTime(LocalDateTime.now());
            messageMapper.insert(message);
        } catch (Exception e) {
            log.warn("确认结果写入会话失败 sessionId={} msg={}", sessionId, e.getMessage());
        }
    }

    /**
     * 轨迹落库：异常不影响对话主流程
     */
    private void persistTrace(String sessionId, String tenantId, String userId, List<AgentStepVO> steps) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isTracePersist() || steps == null || steps.isEmpty()) {
            return;
        }
        String traceId = IdUtil.fastSimpleUUID();
        try {
            for (AgentStepVO step : steps) {
                AssistantAgentTrace trace = new AssistantAgentTrace();
                trace.setTraceId(traceId);
                trace.setSessionId(sessionId);
                trace.setTenantId(tenantId);
                trace.setUserId(userId);
                trace.setStepNo(step.getStepNo());
                trace.setStepType(step.getType());
                trace.setToolName(step.getToolName());
                trace.setToolArgs(maskPii(StrUtil.maxLength(step.getArguments(), 500)));
                trace.setToolResult(maskPii(StrUtil.maxLength(step.getResult(), 2000)));
                trace.setSuccess(step.isSuccess() ? 1 : 0);
                trace.setLatencyMs(step.getLatencyMs());
                trace.setCreateTime(LocalDateTime.now());
                traceMapper.insert(trace);
            }
        } catch (Exception e) {
            log.warn("Agent 轨迹落库失败 sessionId={} msg={}", sessionId, e.getMessage());
        }
    }
}
