package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.service.AssistantCheckpointService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.AgentStepVO;
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
import java.util.regex.Pattern;

/**
 * 断点续跑实现：检查点落 Redis，按租户 + 用户 + 会话隔离，过期自动失效
 * <p>
 * 只记录“可复用结论”的只读证据步骤（工具/知识/子 Agent），写操作不进入检查点，
 * 避免“继续”时把已经执行过的危险动作再放一遍。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantCheckpointServiceImpl implements AssistantCheckpointService {

    /** “继续”类指令：显式续跑，或复述上次没做完的事情 */
    private static final Pattern RESUME_PATTERN = Pattern.compile(
            "^(继续|接着|再继续|继续上次|继续刚才|继续做|接着做|把刚才|把上次|还没做完|上次没做完).*");
    /** 句中提到的“接着上次”类说法，允许用户在一句话里附带新的补充要求 */
    private static final Pattern RESUME_INLINE = Pattern.compile(".*(继续刚才|继续上次|接着刚才|接着上次|上次没做完|还没做完).*");

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Prd2ApiProperties properties;

    @Override
    public void save(String sessionId, String goal, List<DoneStep> done, int stepCount, String reason) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isCheckpointEnabled() || StrUtil.isBlank(sessionId)) {
            return;
        }
        try {
            Snapshot snapshot = new Snapshot(StrUtil.maxLength(StrUtil.blankToDefault(goal, ""), 200),
                    StrUtil.blankToDefault(reason, "未完成"), done == null ? List.of() : done,
                    stepCount, java.time.LocalDateTime.now());
            stringRedisTemplate.opsForValue().set(key(sessionId),
                    objectMapper.writeValueAsString(snapshot),
                    Math.max(60, config.getCheckpointTtlSeconds()), TimeUnit.SECONDS);
            log.info("检查点已保存 session={} done={} reason={}", sessionId, snapshot.done().size(), snapshot.reason());
        } catch (Exception e) {
            log.warn("检查点保存失败 session={} msg={}", sessionId, e.getMessage());
        }
    }

    @Override
    public Snapshot load(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        try {
            String cached = stringRedisTemplate.opsForValue().get(key(sessionId));
            return StrUtil.isBlank(cached) ? null : objectMapper.readValue(cached, Snapshot.class);
        } catch (Exception e) {
            log.warn("检查点读取失败 session={} msg={}", sessionId, e.getMessage());
            return null;
        }
    }

    @Override
    public void clear(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }
        try {
            stringRedisTemplate.delete(key(sessionId));
        } catch (Exception e) {
            log.warn("检查点清理失败 session={} msg={}", sessionId, e.getMessage());
        }
    }

    @Override
    public boolean resumeIntent(String message) {
        String compact = StrUtil.blankToDefault(message, "").replaceAll("\\s+", "");
        if (compact.isEmpty() || compact.length() > 80) {
            return false;
        }
        // 只有存在检查点时续跑才会生效，这里放宽长度是为了允许“继续，顺便把 X 也做了”这类补充说法
        return RESUME_PATTERN.matcher(compact).matches() || RESUME_INLINE.matcher(compact).matches();
    }

    @Override
    public List<DoneStep> merge(List<DoneStep> previous, List<AgentStepVO> steps) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        int max = config == null ? 10 : Math.max(1, config.getCheckpointMaxSteps());
        Map<String, DoneStep> merged = new LinkedHashMap<>();
        if (previous != null) {
            for (DoneStep item : previous) {
                merged.putIfAbsent(item.signature(), item);
            }
        }
        if (steps != null) {
            for (AgentStepVO step : steps) {
                if (!step.isSuccess() || StrUtil.isBlank(step.getToolName())) {
                    continue;
                }
                String type = StrUtil.blankToDefault(step.getType(), "");
                if (!"TOOL".equals(type) && !"KNOWLEDGE".equals(type) && !"SUBAGENT".equals(type)) {
                    continue;
                }
                DoneStep item = new DoneStep(step.getToolName(),
                        StrUtil.maxLength(StrUtil.blankToDefault(step.getArguments(), ""), 160),
                        AssistantCheckpointService.signature(step.getToolName(), step.getArguments()),
                        StrUtil.maxLength(StrUtil.blankToDefault(step.getResult(), ""), 400),
                        StrUtil.blankToDefault(step.getSource(), ""));
                merged.putIfAbsent(item.signature(), item);
            }
        }
        List<DoneStep> result = new ArrayList<>(merged.values());
        // 只保留最近的若干步，避免检查点无限膨胀；早期步骤的价值已被后续结论覆盖
        if (result.size() > max) {
            result = new ArrayList<>(result.subList(result.size() - max, result.size()));
        }
        return result;
    }

    private String key(String sessionId) {
        String tenantId = StrUtil.blankToDefault(TenantContextUtil.getTenantId(), "default");
        String userKey = StrUtil.blankToDefault(TenantContextUtil.getUserKey(),
                StrUtil.blankToDefault(TenantContextUtil.getUserId(), "anon"));
        return RedisKeyConstant.ASSISTANT_CHECKPOINT_PREFIX + tenantId + ":" + userKey + ":" + sessionId;
    }
}
