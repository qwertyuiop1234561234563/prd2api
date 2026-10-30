package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.service.AssistantConfirmationService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 确认票据实现：Redis 存储 + 用后即焚 + 租户/用户绑定
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantConfirmationServiceImpl implements AssistantConfirmationService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Prd2ApiProperties properties;

    @Override
    public String create(String sessionId, String toolName, String arguments, String summary) {
        String confirmId = IdUtil.fastSimpleUUID();
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        Map<String, String> payload = Map.of(
                "sessionId", StrUtil.blankToDefault(sessionId, ""),
                "toolName", toolName,
                "arguments", StrUtil.blankToDefault(arguments, "{}"),
                "summary", StrUtil.blankToDefault(summary, ""));
        try {
            int ttl = Math.max(30, properties.getAgent().getConfirmTtlSeconds());
            stringRedisTemplate.opsForValue().set(key(tenantId, userId, confirmId),
                    objectMapper.writeValueAsString(payload), ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.SYSTEM_ERROR, "确认票据生成失败", e);
        }
        log.info("生成危险操作确认票据 tool={} tenant={} user={} confirmId={}",
                toolName, tenantId, userId, confirmId);
        return confirmId;
    }

    @Override
    public PendingConfirm consume(String confirmId) {
        if (StrUtil.isBlank(confirmId)) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "缺少确认票据");
        }
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        String redisKey = key(tenantId, userId, confirmId);
        // 先取后删：Redis 单命令保证并发下只有一次消费成功
        String cached = stringRedisTemplate.opsForValue().getAndDelete(redisKey);
        if (StrUtil.isBlank(cached)) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "确认票据已过期或已被使用，请重新发起");
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(cached, Map.class);
            return new PendingConfirm(confirmId,
                    String.valueOf(payload.getOrDefault("sessionId", "")),
                    String.valueOf(payload.getOrDefault("toolName", "")),
                    String.valueOf(payload.getOrDefault("arguments", "{}")),
                    String.valueOf(payload.getOrDefault("summary", "")),
                    tenantId, userId);
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.SYSTEM_ERROR, "确认票据解析失败", e);
        }
    }

    private String key(String tenantId, String userId, String confirmId) {
        return RedisKeyConstant.ASSISTANT_CONFIRM_PREFIX + tenantId + ":" + userId + ":" + confirmId;
    }
}
