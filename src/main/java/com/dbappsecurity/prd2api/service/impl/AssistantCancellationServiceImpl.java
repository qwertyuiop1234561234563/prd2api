package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.service.AssistantCancellationService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 中断标记实现：Redis 按租户+用户存一个带时间戳的短生命周期标记
 * <p>
 * 值格式 {@code 写入毫秒|作用域}，作用域为会话 ID 或 *（用户当前运行的一轮）。
 * 只有写入时间不早于本轮开始时间的标记才会命中，避免上一轮遗留的取消误伤新请求。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantCancellationServiceImpl implements AssistantCancellationService {

    /** 用户级通配标记：取消当前运行的一轮 */
    private static final String WILDCARD = "*";

    private final StringRedisTemplate stringRedisTemplate;
    private final Prd2ApiProperties properties;

    @Override
    public void request(String sessionId) {
        try {
            int ttl = Math.max(30, properties.getAgent().getCancelMarkerTtlSeconds());
            String value = System.currentTimeMillis() + "|" + StrUtil.blankToDefault(sessionId, WILDCARD);
            stringRedisTemplate.opsForValue().set(redisKey(), value, ttl, TimeUnit.SECONDS);
            log.info("收到中断请求 sessionId={}", StrUtil.blankToDefault(sessionId, WILDCARD));
        } catch (Exception e) {
            log.warn("中断标记写入失败 msg={}", e.getMessage());
        }
    }

    @Override
    public boolean requested(String sessionId, long startedAt) {
        try {
            String value = stringRedisTemplate.opsForValue().get(redisKey());
            if (StrUtil.isBlank(value)) {
                return false;
            }
            int split = value.indexOf('|');
            if (split <= 0) {
                return false;
            }
            long markedAt = Long.parseLong(value.substring(0, split));
            String scope = value.substring(split + 1);
            boolean hit = markedAt >= startedAt
                    && (WILDCARD.equals(scope) || StrUtil.equals(scope, StrUtil.blankToDefault(sessionId, "")));
            log.debug("中断标记检查 markedAt={} startedAt={} scope={} hit={}", markedAt, startedAt, scope, hit);
            return hit;
        } catch (Exception e) {
            // Redis 不可用或标记格式异常时按未中断处理，宁可继续执行也不误杀正常对话
            return false;
        }
    }

    private String redisKey() {
        String tenantId = StrUtil.blankToDefault(TenantContextUtil.getTenantId(), "-");
        String userKey = StrUtil.blankToDefault(TenantContextUtil.getUserKey(), TenantContextUtil.getUserId());
        return RedisKeyConstant.assistantCancel(tenantId, StrUtil.blankToDefault(userKey, "-"));
    }
}
