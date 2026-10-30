package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.entity.AssistantChatMessage;
import com.dbappsecurity.prd2api.service.AssistantContextService;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 上下文工程实现
 * <p>
 * token 估算采用中文字符≈1 token、其它字符≈3 字符/token 的保守近似；
 * 超预算时优先把早期对话交给模型做增量摘要（摘要结果按会话缓存），失败则降级为截断丢弃。
 * 压缩本身不改变工具执行结果，只控制“送进模型的历史”。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantContextServiceImpl implements AssistantContextService {

    private static final int MAX_TURN_CHARS = 600;
    private static final int MAX_SUMMARY_INPUT_CHARS = 500;
    private static final long SUMMARY_TTL_HOURS = 24L;

    private static final String SUMMARY_SYSTEM = """
            你是对话压缩器。把多轮历史压缩成客观、可复用的事实摘要。
            只保留用户身份/偏好、已确认的结论、未完成任务及其 ID；不要编造、不要评论、不要输出无关寒暄。
            """;

    private final LlmInvokeService llmInvokeService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Prd2ApiProperties properties;
    private final MetricUtil metricUtil;

    @Override
    public ContextResult build(String sessionId, String systemPrompt,
                               List<AssistantChatMessage> history, String message) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        int budget = config == null ? 6000 : Math.max(500, config.getContextTokenBudget());
        List<AssistantChatMessage> safeHistory = history == null ? List.of() : history;

        int rawTokens = estimateTokens(systemPrompt) + estimateTokens(message);
        for (AssistantChatMessage item : safeHistory) {
            rawTokens += estimateTokens(StrUtil.maxLength(item.getContent(), MAX_TURN_CHARS));
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        String finalPrompt = systemPrompt;
        int summarized = 0;
        int dropped = 0;
        boolean compressible = config != null && config.isContextSummary();
        if (compressible && rawTokens > budget && safeHistory.size() > 1) {
            int keep = Math.max(1, config.getContextKeepRecent());
            int from = Math.max(0, safeHistory.size() - keep);
            List<AssistantChatMessage> older = safeHistory.subList(0, from);
            String summary = summarize(sessionId, older, config);
            if (StrUtil.isNotBlank(summary)) {
                finalPrompt = systemPrompt + "\n【历史对话摘要】\n" + summary;
                summarized = older.size();
            } else {
                // 摘要不可用（模型故障/未配置）时降级为直接丢弃，保证本轮仍能发出
                dropped += older.size();
            }
            for (int i = from; i < safeHistory.size(); i++) {
                messages.add(historyMessage(safeHistory.get(i)));
            }
        } else {
            for (AssistantChatMessage item : safeHistory) {
                messages.add(historyMessage(item));
            }
        }
        messages.add(Map.of("role", "user", "content", message));

        int droppedNow = trimToBudget(messages, finalPrompt, budget);
        dropped += droppedNow;
        int finalTokens = tokensOf(finalPrompt, messages);
        boolean compressed = summarized > 0 || dropped > 0;
        if (compressed) {
            metricUtil.agentContextCompressed(Math.max(0, rawTokens - finalTokens));
            log.info("上下文压缩 sessionId={} raw={} final={} budget={} summarized={} dropped={}",
                    sessionId, rawTokens, finalTokens, budget, summarized, dropped);
        }
        return new ContextResult(finalPrompt, messages, rawTokens, finalTokens,
                compressed, summarized, dropped);
    }

    /**
     * 超出预算时从最老的历史消息开始丢弃，当前问题始终保留
     */
    private int trimToBudget(List<Map<String, Object>> messages, String systemPrompt, int budget) {
        int dropped = 0;
        while (messages.size() > 1 && tokensOf(systemPrompt, messages) > budget) {
            messages.remove(0);
            dropped++;
        }
        return dropped;
    }

    /**
     * 早期对话摘要：优先复用 Redis 中该会话的增量摘要，避免每轮重复付费
     */
    private String summarize(String sessionId, List<AssistantChatMessage> older,
                             Prd2ApiProperties.Agent config) {
        if (older.isEmpty() || StrUtil.isBlank(sessionId)) {
            return null;
        }
        long lastId = messageId(older.get(older.size() - 1));
        String key = RedisKeyConstant.ASSISTANT_CTX_SUMMARY_PREFIX + sessionId;
        String previous = null;
        long previousId = -1L;
        String cached = redisGet(key);
        if (StrUtil.isNotBlank(cached)) {
            try {
                JsonNode node = objectMapper.readTree(cached);
                previousId = node.path("lastId").asLong(-1L);
                previous = node.path("summary").asText(null);
                if (previousId >= lastId && StrUtil.isNotBlank(previous)) {
                    return previous;
                }
            } catch (Exception e) {
                log.debug("历史摘要缓存解析失败，重新生成 key={}", key);
            }
        }

        StringBuilder input = new StringBuilder();
        if (StrUtil.isNotBlank(previous)) {
            input.append("【已有摘要】\n").append(previous).append('\n');
        }
        input.append("【新增对话】\n");
        for (AssistantChatMessage item : older) {
            String role = AssistantChatMessage.ROLE_USER.equals(item.getRole()) ? "用户" : "助手";
            input.append(role).append("：")
                    .append(StrUtil.maxLength(item.getContent(), MAX_SUMMARY_INPUT_CHARS)).append('\n');
        }
        input.append("请输出合并后的摘要，不超过 ")
                .append(Math.max(120, config.getContextSummaryMaxChars())).append(" 字。");
        try {
            String summary = llmInvokeService.chat(SUMMARY_SYSTEM, input.toString(), "default");
            if (StrUtil.isBlank(summary)) {
                return StrUtil.blankToDefault(previous, null);
            }
            writeSummary(key, lastId, summary);
            return summary.trim();
        } catch (Exception e) {
            log.warn("历史摘要生成失败，降级截断 msg={}", e.getMessage());
            return StrUtil.blankToDefault(previous, null);
        }
    }

    private void writeSummary(String key, long lastId, String summary) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lastId", lastId);
            payload.put("summary", summary);
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(payload),
                    SUMMARY_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.debug("历史摘要缓存写入失败 key={}", key);
        }
    }

    private String redisGet(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            return null;
        }
    }

    private long messageId(AssistantChatMessage message) {
        return message.getId() == null ? 0L : message.getId();
    }

    private Map<String, Object> historyMessage(AssistantChatMessage item) {
        String role = AssistantChatMessage.ROLE_USER.equals(item.getRole()) ? "user" : "assistant";
        return Map.of("role", role, "content", StrUtil.maxLength(item.getContent(), MAX_TURN_CHARS));
    }

    private int tokensOf(String systemPrompt, List<Map<String, Object>> messages) {
        int total = estimateTokens(systemPrompt);
        for (Map<String, Object> item : messages) {
            Object content = item.get("content");
            total += estimateTokens(content == null ? "" : content.toString());
        }
        return total;
    }

    /**
     * 保守 token 估算：中文/全角字符约 1 token，其余约 3 字符 1 token
     */
    static int estimateTokens(String text) {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        int cjk = 0;
        int ascii = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            boolean wide = (ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3000 && ch <= 0x303F)
                    || (ch >= 0xFF00 && ch <= 0xFFEF);
            if (wide) {
                cjk++;
            } else if (!Character.isWhitespace(ch)) {
                ascii++;
            }
        }
        return cjk + (int) Math.ceil(ascii / 3.0D);
    }
}
