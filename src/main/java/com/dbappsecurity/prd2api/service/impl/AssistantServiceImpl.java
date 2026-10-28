package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.AssistantActionType;
import com.dbappsecurity.prd2api.constant.AssistantIntentEnum;
import com.dbappsecurity.prd2api.constant.AssistantPromptConstant;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.dto.AssistantChatDTO;
import com.dbappsecurity.prd2api.entity.AssistantChatMessage;
import com.dbappsecurity.prd2api.entity.AssistantChatSession;
import com.dbappsecurity.prd2api.mapper.AssistantChatMessageMapper;
import com.dbappsecurity.prd2api.mapper.AssistantChatSessionMapper;
import com.dbappsecurity.prd2api.service.AssistantActionService;
import com.dbappsecurity.prd2api.service.AssistantService;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import com.dbappsecurity.prd2api.util.AssistantIntentResolver;
import com.dbappsecurity.prd2api.util.RateLimitUtil;
import com.dbappsecurity.prd2api.util.SensitiveFilterUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.AssistantMessageVO;
import com.dbappsecurity.prd2api.vo.AssistantReplyVO;
import com.dbappsecurity.prd2api.vo.AssistantSessionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    private static final String GREETING_REPLY = "我是小 P，Prd2Api 的桌面助手。可以帮你查任务进度、额度、"
            + "文件和会员信息，也能解答上传、导出、四阶段解析的问题。试试问我“我的任务”。";
    private static final String FALLBACK_REPLY = "这个问题我暂时没有把握回答。你可以问我：我的任务、我的额度、"
            + "文件列表、VIP 权益，或者上传和导出的具体步骤。";
    private static final int MAX_PAGE_SIZE = 50;

    private final AssistantChatSessionMapper sessionMapper;
    private final AssistantChatMessageMapper messageMapper;
    private final AssistantActionService actionService;
    private final AssistantIntentResolver intentResolver;
    private final LlmInvokeService llmInvokeService;
    private final SensitiveFilterUtil sensitiveFilterUtil;
    private final RateLimitUtil rateLimitUtil;
    private final Prd2ApiProperties properties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantReplyVO chat(AssistantChatDTO dto) {
        Prd2ApiProperties.Assistant config = properties.getAssistant();
        if (config == null || !config.isEnabled()) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "智能助手暂未启用");
        }
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        String userKey = StrUtil.blankToDefault(TenantContextUtil.getUserKey(), userId);
        rateLimitUtil.check(RedisKeyConstant.limitAssistant(tenantId, userKey),
                config.getRateLimitPerMinute());

        String message = sensitiveFilterUtil.mask(dto.getMessage()).trim();
        AssistantChatSession session = loadOrCreateSession(dto.getSessionId(), tenantId, userId, message);
        List<AssistantChatMessage> history = loadRecentMessages(session.getSessionId(), config.getHistoryLimit());

        AssistantChatMessage userMessage = new AssistantChatMessage();
        userMessage.setSessionId(session.getSessionId());
        userMessage.setTenantId(tenantId);
        userMessage.setUserId(userId);
        userMessage.setRole(AssistantChatMessage.ROLE_USER);
        userMessage.setContent(message);
        userMessage.setCreateTime(LocalDateTime.now());
        messageMapper.insert(userMessage);

        AssistantIntentEnum intent = intentResolver.resolve(message);
        AssistantReplyVO.Action action = null;
        String reply;
        boolean degraded = false;
        if (actionService.executable(intent)) {
            AssistantActionService.ActionResult result = actionService.execute(intent, message);
            reply = result.reply();
            action = buildAction(result.actionType(), result.actionLabel(), result.actionTarget(), result.data());
        } else if (intent == AssistantIntentEnum.GREETING) {
            reply = GREETING_REPLY;
            action = buildAction(AssistantActionType.NONE, null, null, null);
        } else {
            QaAnswer answer = answerQuestion(message, history, config);
            reply = answer.reply();
            degraded = answer.degraded();
            action = buildAction(AssistantActionType.NONE, null, null, null);
        }

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

        AssistantReplyVO vo = new AssistantReplyVO();
        vo.setSessionId(session.getSessionId());
        vo.setMessageId(assistantMessage.getId());
        vo.setReply(reply);
        vo.setIntent(intent.getCode());
        vo.setIntentLabel(intent.getLabel());
        vo.setAction(action);
        vo.setSuggestions(suggestionsFor(intent));
        vo.setDegraded(degraded);
        vo.setTime(assistantMessage.getCreateTime());
        return vo;
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

    /**
     * 自由问答：优先大模型，未配置或失败时回落内置知识库
     */
    private QaAnswer answerQuestion(String message, List<AssistantChatMessage> history,
                                    Prd2ApiProperties.Assistant config) {
        if (llmInvokeService.available()) {
            try {
                String reply = llmInvokeService.chat(AssistantPromptConstant.SYSTEM_PROMPT,
                        buildUserPrompt(message, history, config.getHistoryLimit()), "default");
                return new QaAnswer(reply, false);
            } catch (BizException e) {
                log.warn("助手大模型调用失败，回落内置知识库 msg={}", e.getMessage());
            }
        }
        return new QaAnswer(faqAnswer(message), true);
    }

    private String buildUserPrompt(String message, List<AssistantChatMessage> history, int historyLimit) {
        StringBuilder prompt = new StringBuilder();
        if (!history.isEmpty()) {
            prompt.append("【最近对话】\n");
            int from = Math.max(0, history.size() - Math.max(2, historyLimit));
            for (int i = from; i < history.size(); i++) {
                AssistantChatMessage item = history.get(i);
                String role = AssistantChatMessage.ROLE_USER.equals(item.getRole()) ? "用户" : "助手";
                prompt.append(role).append("：")
                        .append(StrUtil.maxLength(item.getContent(), 300)).append('\n');
            }
        }
        prompt.append("【当前问题】\n").append(message);
        return prompt.toString();
    }

    private String faqAnswer(String message) {
        String lower = message.toLowerCase();
        for (AssistantPromptConstant.Faq faq : AssistantPromptConstant.FAQ) {
            for (String keyword : faq.getKeywords()) {
                if (StrUtil.isNotBlank(keyword) && lower.contains(keyword.trim().toLowerCase())) {
                    return faq.getAnswer();
                }
            }
        }
        return FALLBACK_REPLY;
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

    /**
     * 问答结果：degraded 表示走的是内置知识库
     */
    private record QaAnswer(String reply, boolean degraded) {
    }
}
