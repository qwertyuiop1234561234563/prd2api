package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.config.SentinelConfig;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.LlmModelEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import com.dbappsecurity.prd2api.service.QuotaResolver;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.RateLimitUtil;
import com.dbappsecurity.prd2api.util.SensitiveFilterUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * LLM 调用实现：超时控制、指数退避重试、Sentinel 熔断、token 统计与配额扣减
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmInvokeServiceImpl implements LlmInvokeService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final Prd2ApiProperties properties;
    private final QuotaResolver quotaResolver;
    private final MetricUtil metricUtil;
    private final RateLimitUtil rateLimitUtil;
    private final SensitiveFilterUtil sensitiveFilterUtil;

    @Override
    public String chat(String systemPrompt, String userPrompt, String modelCode) {
        Prd2ApiProperties.Llm llm = properties.getLlm();
        if (StrUtil.isBlank(llm.getApiKey())) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR,
                    "未配置 LLM_API_KEY（环境变量注入），无法执行智能解析");
        }
        String modelName = LlmModelEnum.resolve(modelCode).modelNameOrDefault(llm.getModelName());

        String tenantId = TenantContextUtil.getTenantId();
        if (StrUtil.isNotBlank(tenantId)) {
            rateLimitUtil.check(RedisKeyConstant.limitLlm(tenantId, TenantContextUtil.getUserKey()),
                    properties.getLimit().getLlmPerMinute());
        }

        List<String> hits = sensitiveFilterUtil.find(userPrompt);
        if (!hits.isEmpty()) {
            log.warn("LLM 入参命中敏感词 {}，已做掩码处理 tenantId={}", hits, tenantId);
            userPrompt = sensitiveFilterUtil.mask(userPrompt);
        }

        Entry entry = null;
        try {
            entry = SphU.entry(SentinelConfig.RES_LLM);
            return invokeWithRetry(systemPrompt, userPrompt, modelName);
        } catch (BlockException e) {
            log.warn("LLM 调用被 Sentinel 限流 tenantId={}", tenantId);
            throw new BizException(BizErrorCodeEnum.RATE_LIMITED, "LLM 调用并发过高，请稍后重试");
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    @Override
    public boolean available() {
        return StrUtil.isNotBlank(properties.getLlm().getApiKey());
    }

    private String invokeWithRetry(String systemPrompt, String userPrompt, String modelName) {
        int maxRetry = Math.max(0, properties.getLlm().getMaxRetry());
        long backoff = properties.getLlm().getRetryBackoffMs();
        RetryableLlmException lastError = null;
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                return callOnce(systemPrompt, userPrompt, modelName);
            } catch (RetryableLlmException e) {
                // 网络抖动、超时、429、5xx 才重试
                lastError = e;
                log.warn("LLM 可重试失败 attempt={}/{} msg={}", attempt + 1, maxRetry + 1, e.getMessage());
                if (attempt < maxRetry) {
                    sleep(backoff * (long) Math.pow(2, attempt));
                }
            }
            // 其余 BizException（配额、鉴权、参数）直接抛出，由外层处理
        }
        throw new BizException(BizErrorCodeEnum.BIZ_ERROR,
                "LLM 调用失败（已重试 " + maxRetry + " 次）: "
                        + (lastError == null ? "unknown" : lastError.getMessage()));
    }

    /**
     * 可重试异常：仅用于网络异常、429 与 5xx，区别于不可重试的业务异常
     */
    private static class RetryableLlmException extends RuntimeException {
        RetryableLlmException(String message) {
            super(message);
        }

        RetryableLlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private String callOnce(String systemPrompt, String userPrompt, String modelName) {
        Prd2ApiProperties.Llm llm = properties.getLlm();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelName);
        body.put("temperature", llm.getTemperature());
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.SYSTEM_ERROR, "LLM 请求体序列化失败", e);
        }
        Request request = new Request.Builder()
                .url(StrUtil.removeSuffix(llm.getBaseUrl(), "/") + "/chat/completions")
                .header("Authorization", "Bearer " + llm.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload, JSON))
                .build();

        long start = System.currentTimeMillis();
        String responseBody;
        int httpCode;
        try (Response response = okHttpClient.newCall(request).execute()) {
            httpCode = response.code();
            responseBody = response.body() == null ? "" : response.body().string();
        } catch (Exception e) {
            throw new RetryableLlmException("LLM 网络调用异常: " + e.getMessage(), e);
        } finally {
            metricUtil.llmLatency(System.currentTimeMillis() - start);
        }
        if (httpCode == 429 || httpCode >= 500) {
            throw new RetryableLlmException("LLM 服务暂时不可用 http=" + httpCode
                    + " body=" + StrUtil.maxLength(responseBody, 300));
        }
        if (httpCode < 200 || httpCode >= 300) {
            // 4xx 多为鉴权/参数问题，重试无意义
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR,
                    "LLM 调用返回异常 http=" + httpCode + " body=" + StrUtil.maxLength(responseBody, 300));
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (StrUtil.isBlank(content)) {
                throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "LLM 返回内容为空");
            }
            long tokens = root.path("usage").path("total_tokens").asLong(0L);
            metricUtil.addTokens(tokens);
            if (tokens > 0) {
                String tenantId = TenantContextUtil.getTenantId();
                if (StrUtil.isNotBlank(tenantId)) {
                    quotaResolver.consumeTokens(tokens);
                }
            }
            log.info("LLM 调用完成 model={} tokens={} cost={}ms", modelName, tokens,
                    System.currentTimeMillis() - start);
            return content.trim();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "LLM 响应解析失败: " + e.getMessage(), e);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(Math.min(millis, 5000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
