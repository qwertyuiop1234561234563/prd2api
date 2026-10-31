package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.dto.AssistantFeedbackDTO;
import com.dbappsecurity.prd2api.entity.AssistantAgentTrace;
import com.dbappsecurity.prd2api.entity.AssistantChatMessage;
import com.dbappsecurity.prd2api.entity.AssistantChatSession;
import com.dbappsecurity.prd2api.mapper.AssistantAgentTraceMapper;
import com.dbappsecurity.prd2api.mapper.AssistantChatMessageMapper;
import com.dbappsecurity.prd2api.mapper.AssistantChatSessionMapper;
import com.dbappsecurity.prd2api.service.AssistantFailureCaseService;
import com.dbappsecurity.prd2api.service.AssistantFeedbackService;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.PiiMaskUtil;
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
 * 用户反馈闭环实现
 * <p>
 * 点赞只做统计；点踩额外做两件事：把原因打标进指标，并把该轮可安全重放的只读工具步骤
 * 转成回归用例（source=FEEDBACK），后续可被 /eval/candidates/run 一键重放。
 * 这样“用户的一次抱怨”会变成“一条可重复验证的质量资产”，而不是沉在聊天记录里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantFeedbackServiceImpl implements AssistantFeedbackService {

    private static final String UP = "UP";
    private static final String DOWN = "DOWN";
    private static final Set<String> REASONS = Set.of("INACCURATE", "INCOMPLETE", "SLOW", "UNSAFE", "OTHER");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_FEEDBACK = 200;
    private static final long TTL_SECONDS = TimeUnit.DAYS.toSeconds(180);

    /** 与回放/挖掘保持一致：只有只读工具才允许进入可重放用例 */
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "search_knowledge", "recall_memory", "query_my_tasks", "get_task_status", "query_my_quota",
            "query_my_vip", "query_admin_stats", "list_my_files");

    private final AssistantChatSessionMapper sessionMapper;
    private final AssistantChatMessageMapper messageMapper;
    private final AssistantAgentTraceMapper traceMapper;
    private final AssistantFailureCaseService failureCaseService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final MetricUtil metricUtil;
    private final Prd2ApiProperties properties;

    @Override
    public Result submit(AssistantFeedbackDTO dto) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isFeedbackEnabled()) {
            return new Result("", rating(dto.getRating()), "OTHER", false, "", "反馈功能已关闭");
        }
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        AssistantChatSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AssistantChatSession>()
                .eq(AssistantChatSession::getSessionId, dto.getSessionId())
                .eq(AssistantChatSession::getTenantId, tenantId)
                .eq(AssistantChatSession::getUserId, userId));
        if (session == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "会话不存在或不属于当前用户");
        }
        String rating = rating(dto.getRating());
        String reason = normalizeReason(dto.getReason(), rating);
        AssistantChatMessage message = locateMessage(session.getSessionId(), dto.getMessageId());
        String feedbackId = DigestUtil.md5Hex("feedback|" + session.getSessionId() + "|"
                + (message == null ? "latest" : message.getId())).substring(0, 12);
        FeedbackRecord record = new FeedbackRecord(feedbackId, session.getSessionId(),
                message == null ? null : message.getId(), rating, reason,
                PiiMaskUtil.mask(StrUtil.maxLength(dto.getComment(), 200)),
                LocalDateTime.now().format(TIME_FORMAT));
        save(record);
        metricUtil.agentFeedback(rating, reason);
        if (!DOWN.equals(rating)) {
            return new Result(feedbackId, rating, reason, false, "", "已记录，感谢反馈");
        }
        CaseDraft draft = draftCase(session.getSessionId(), tenantId, message, reason);
        if (draft == null) {
            metricUtil.agentFeedbackCase("skipped");
            return new Result(feedbackId, rating, reason, false, "", "已记录；该轮没有可重放的只读工具步骤");
        }
        int stored = failureCaseService.store(List.of(draft.item()));
        metricUtil.agentFeedbackCase(stored > 0 ? "created" : "duplicate");
        return new Result(feedbackId, rating, reason, stored > 0, draft.item().id(),
                stored > 0 ? "已转成回归用例，可用 /eval/candidates/run 重放" : "同一条回复已存在回归用例");
    }

    @Override
    public Stats stats() {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(feedbackKey());
        int up = 0;
        int down = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (Object value : entries.values()) {
            FeedbackRecord record = read(value);
            if (record == null) {
                continue;
            }
            if (DOWN.equals(record.rating())) {
                down++;
                reasons.merge(record.reason(), 1, Integer::sum);
            } else {
                up++;
            }
        }
        int total = up + down;
        List<ReasonCount> reasonCounts = new ArrayList<>();
        reasons.forEach((key, count) -> reasonCounts.add(new ReasonCount(key, count)));
        int feedbackCases = (int) failureCaseService.candidates().stream()
                .filter(item -> "FEEDBACK".equals(item.source())).count();
        double downRate = total == 0 ? 0.0D : (double) down / total;
        return new Stats(total, up, down, Math.round(downRate * 100) / 100.0D, reasonCounts, feedbackCases);
    }

    private String rating(String value) {
        return UP.equalsIgnoreCase(StrUtil.blankToDefault(value, "")) ? UP : DOWN;
    }

    private String normalizeReason(String value, String rating) {
        if (UP.equals(rating)) {
            return "OTHER";
        }
        String reason = StrUtil.blankToDefault(value, "OTHER").toUpperCase();
        return REASONS.contains(reason) ? reason : "OTHER";
    }

    /**
     * 定位被评价的助手消息；不传 messageId 时取最近一条助手回复
     */
    private AssistantChatMessage locateMessage(String sessionId, Long messageId) {
        LambdaQueryWrapper<AssistantChatMessage> wrapper = new LambdaQueryWrapper<AssistantChatMessage>()
                .eq(AssistantChatMessage::getSessionId, sessionId)
                .eq(AssistantChatMessage::getRole, AssistantChatMessage.ROLE_ASSISTANT);
        if (messageId != null) {
            wrapper.eq(AssistantChatMessage::getId, messageId);
        } else {
            wrapper.orderByDesc(AssistantChatMessage::getId).last("limit 1");
        }
        return messageMapper.selectOne(wrapper);
    }

    /**
     * 把点踩的那一轮轨迹里的只读步骤转成回归用例
     * <p>
     * 轨迹与消息没有外键，用“消息时间后 60 秒内最近的一组轨迹”作为该轮，容忍落库顺序差异。
     */
    private CaseDraft draftCase(String sessionId, String tenantId, AssistantChatMessage message, String reason) {
        if (message == null) {
            return null;
        }
        LocalDateTime upper = message.getCreateTime() == null
                ? LocalDateTime.now().plusSeconds(60) : message.getCreateTime().plusSeconds(60);
        AssistantAgentTrace anchor = traceMapper.selectOne(new LambdaQueryWrapper<AssistantAgentTrace>()
                .eq(AssistantAgentTrace::getSessionId, sessionId)
                .eq(AssistantAgentTrace::getTenantId, tenantId)
                .le(AssistantAgentTrace::getCreateTime, upper)
                .orderByDesc(AssistantAgentTrace::getId)
                .last("limit 1"));
        if (anchor == null || StrUtil.isBlank(anchor.getTraceId())) {
            return null;
        }
        List<AssistantAgentTrace> round = traceMapper.selectList(new LambdaQueryWrapper<AssistantAgentTrace>()
                .eq(AssistantAgentTrace::getTraceId, anchor.getTraceId())
                .orderByAsc(AssistantAgentTrace::getStepNo));
        AssistantAgentTrace step = round.stream()
                .filter(item -> READ_ONLY_TOOLS.contains(StrUtil.blankToDefault(item.getToolName(), ""))
                        && ("TOOL".equals(item.getStepType()) || "KNOWLEDGE".equals(item.getStepType())))
                .findFirst()
                .orElse(null);
        if (step == null) {
            return null;
        }
        String caseId = DigestUtil.md5Hex("feedback-case|" + message.getId()).substring(0, 12);
        String question = StrUtil.maxLength(questionOf(sessionId), 80);
        String error = "用户点踩：" + reasonText(reason);
        return new CaseDraft(new AssistantFailureCaseService.Case(caseId, question, step.getToolName(),
                StrUtil.blankToDefault(step.getToolArgs(), "{}"), error, 1,
                LocalDateTime.now().format(TIME_FORMAT), "FEEDBACK"));
    }

    private String questionOf(String sessionId) {
        AssistantChatMessage message = messageMapper.selectOne(new LambdaQueryWrapper<AssistantChatMessage>()
                .eq(AssistantChatMessage::getSessionId, sessionId)
                .eq(AssistantChatMessage::getRole, AssistantChatMessage.ROLE_USER)
                .orderByDesc(AssistantChatMessage::getId)
                .last("limit 1"));
        return message == null ? "" : message.getContent();
    }

    private String reasonText(String reason) {
        return switch (reason) {
            case "INACCURATE" -> "答案不准确";
            case "INCOMPLETE" -> "回答不完整";
            case "SLOW" -> "响应太慢";
            case "UNSAFE" -> "回答存在风险";
            default -> "其他原因";
        };
    }

    private void save(FeedbackRecord record) {
        String key = feedbackKey();
        try {
            stringRedisTemplate.opsForHash().put(key, record.id(), objectMapper.writeValueAsString(record));
            stringRedisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
            trim(key);
        } catch (Exception e) {
            log.warn("反馈写入失败 id={} msg={}", record.id(), e.getMessage());
        }
    }

    /**
     * 反馈按时间倒序只保留最近 N 条，避免 Redis 无限增长
     */
    private void trim(String key) {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        if (entries.size() <= MAX_FEEDBACK) {
            return;
        }
        List<FeedbackRecord> all = new ArrayList<>();
        for (Object value : entries.values()) {
            FeedbackRecord record = read(value);
            if (record != null) {
                all.add(record);
            }
        }
        all.sort((left, right) -> right.createdAt().compareTo(left.createdAt()));
        for (int i = MAX_FEEDBACK; i < all.size(); i++) {
            stringRedisTemplate.opsForHash().delete(key, all.get(i).id());
        }
    }

    private FeedbackRecord read(Object value) {
        try {
            return objectMapper.readValue(String.valueOf(value), FeedbackRecord.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String feedbackKey() {
        String tenantId = TenantContextUtil.requireTenantId();
        String userKey = StrUtil.blankToDefault(TenantContextUtil.getUserKey(),
                TenantContextUtil.requireUserId());
        return RedisKeyConstant.ASSISTANT_FEEDBACK_PREFIX + tenantId + ":" + userKey;
    }

    /**
     * 反馈记录（Redis Hash 存储，按租户+用户隔离）
     */
    private record FeedbackRecord(String id, String sessionId, Long messageId, String rating,
                                  String reason, String comment, String createdAt) {
    }

    private record CaseDraft(AssistantFailureCaseService.Case item) {
    }
}
