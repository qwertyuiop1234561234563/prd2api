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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 调用实现：超时控制、指数退避重试、Sentinel 熔断、token 统计与配额扣减
 * <p>
 * 同时支持普通对话与 OpenAI 兼容的 function calling，默认对接智谱 GLM-4-Flash（免费），
 * 通过 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL_NAME 可切换到任意兼容网关。
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
        String modelName = prepare(modelCode);
        String prompt = maskUserPrompt(userPrompt);

        ObjectNode body = baseBody(modelName);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", prompt);

        MessageResult result = invoke(body, modelName);
        if (StrUtil.isBlank(result.content())) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "LLM 返回内容为空");
        }
        return result.content().trim();
    }

    @Override
    public ToolChatResult chatWithTools(List<Map<String, Object>> messages, String toolsJson, String modelCode) {
        String modelName = prepare(modelCode);
        ObjectNode body = baseBody(modelName);
        body.set("messages", objectMapper.valueToTree(messages));
        if (StrUtil.isNotBlank(toolsJson)) {
            try {
                body.set("tools", objectMapper.readTree(toolsJson));
                body.put("tool_choice", "auto");
            } catch (Exception e) {
                throw new BizException(BizErrorCodeEnum.SYSTEM_ERROR, "工具定义序列化失败", e);
            }
        }
        MessageResult result = invoke(body, modelName);
        return new ToolChatResult(result.content(), result.toolCalls(), result.tokens());
    }

    @Override
    public boolean available() {
        return StrUtil.isNotBlank(properties.getLlm().getApiKey());
    }

    @Override
    public boolean toolCallingAvailable() {
        return available() && properties.getLlm().isToolCalling();
    }

    /**
     * 统一前置校验：密钥、模型解析、限流
     */
    private String prepare(String modelCode) {
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
        return modelName;
    }

    private String maskUserPrompt(String userPrompt) {
        List<String> hits = sensitiveFilterUtil.find(userPrompt);
        if (!hits.isEmpty()) {
            log.warn("LLM 入参命中敏感词 {}，已做掩码处理 tenantId={}", hits, TenantContextUtil.getTenantId());
            return sensitiveFilterUtil.mask(userPrompt);
        }
        return userPrompt;
    }

    private ObjectNode baseBody(String modelName) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelName);
        body.put("temperature", properties.getLlm().getTemperature());
        body.put("stream", false);
        return body;
    }

    /**
     * Sentinel 熔断 + 重试
     */
    private MessageResult invoke(ObjectNode body, String modelName) {
        Entry entry = null;
        try {
            entry = SphU.entry(SentinelConfig.RES_LLM);
            return invokeWithRetry(body, modelName);
        } catch (BlockException e) {
            log.warn("LLM 调用被 Sentinel 限流 tenantId={}", TenantContextUtil.getTenantId());
            throw new BizException(BizErrorCodeEnum.RATE_LIMITED, "LLM 调用并发过高，请稍后重试");
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    private MessageResult invokeWithRetry(ObjectNode body, String modelName) {
        int maxRetry = Math.max(0, properties.getLlm().getMaxRetry());
        long backoff = properties.getLlm().getRetryBackoffMs();
        RetryableLlmException lastError = null;
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                return callOnce(body, modelName);
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

    private MessageResult callOnce(ObjectNode body, String modelName) {
        Prd2ApiProperties.Llm llm = properties.getLlm();
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
        } catch (java.net.SocketTimeoutException e) {
            // 读超时多为单次生成本身耗时过长（推理型模型先输出思维链），重试只会成倍放大等待时间，
            // 因此只在连接超时时重试，读超时直接失败并给出可操作提示
            String reason = StrUtil.blankToDefault(e.getMessage(), "timeout");
            if (StrUtil.containsIgnoreCase(reason, "connect")) {
                throw new RetryableLlmException("LLM 连接超时: " + reason, e);
            }
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR,
                    "LLM 调用超时（read-timeout=" + (llm.getReadTimeout() / 1000)
                            + "s 仍未返回），请稍后重试或缩减文档长度");
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
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            List<ToolCall> toolCalls = parseToolCalls(message);

            long tokens = root.path("usage").path("total_tokens").asLong(0L);
            metricUtil.addTokens(tokens);
            if (tokens > 0) {
                String tenantId = TenantContextUtil.getTenantId();
                if (StrUtil.isNotBlank(tenantId)) {
                    quotaResolver.consumeTokens(tokens);
                }
            }
            log.info("LLM 调用完成 model={} tokens={} toolCalls={} cost={}ms",
                    modelName, tokens, toolCalls.size(), System.currentTimeMillis() - start);
            return new MessageResult(content, toolCalls, tokens);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "LLM 响应解析失败: " + e.getMessage(), e);
        }
    }

    private List<ToolCall> parseToolCalls(JsonNode message) {
        List<ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (!toolCalls.isArray()) {
            return calls;
        }
        for (JsonNode call : toolCalls) {
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            if (StrUtil.isBlank(name)) {
                continue;
            }
            calls.add(new ToolCall(call.path("id").asText(""),
                    name,
                    StrUtil.blankToDefault(function.path("arguments").asText(""), "{}")));
        }
        return calls;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(Math.min(millis, 5000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 统一解析结果：文本、工具调用、token
     */
    private record MessageResult(String content, List<ToolCall> toolCalls, long tokens) {
    }
}
