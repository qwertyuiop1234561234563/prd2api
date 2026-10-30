package com.dbappsecurity.prd2api.agent.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolRegistry.Risk;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolRegistry.ToolResult;
import com.dbappsecurity.prd2api.config.AsyncThreadPoolConfig;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.service.LlmInvokeService.ToolCall;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具执行器：同一轮多个工具并行执行、单工具超时保护、只读工具自动重试、写工具幂等缓存
 * <p>
 * 关键点：工具链路依赖 ThreadLocal 中的租户上下文，工作线程必须显式拷贝并清理，
 * 否则并发执行会丢身份（多线程 + 上下文传递最容易踩的坑）。
 */
@Slf4j
@Component
public class AssistantToolExecutor {

    private static final long IDEM_TTL_SECONDS = 120L;

    private final AssistantToolRegistry toolRegistry;
    private final Prd2ApiProperties properties;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final MetricUtil metricUtil;
    private final ThreadPoolTaskExecutor toolExecutor;

    public AssistantToolExecutor(AssistantToolRegistry toolRegistry,
                                 Prd2ApiProperties properties,
                                 StringRedisTemplate stringRedisTemplate,
                                 ObjectMapper objectMapper,
                                 MetricUtil metricUtil,
                                 @Qualifier(AsyncThreadPoolConfig.TOOL_EXECUTOR) ThreadPoolTaskExecutor toolExecutor) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.metricUtil = metricUtil;
        this.toolExecutor = toolExecutor;
    }

    /**
     * 并行执行一批工具调用，返回顺序与模型给出的调用顺序一致
     */
    public List<Execution> executeAll(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        Prd2ApiProperties.Agent config = properties.getAgent();
        // 关键：在调用线程先捕获租户上下文，工作线程里 ThreadLocal 是空的
        Ctx ctx = new Ctx(TenantContextUtil.getTenantId(), TenantContextUtil.getUserId(),
                TenantContextUtil.getUserKey(), TenantContextUtil.getRole());
        if (!config.isParallelTools() || calls.size() == 1) {
            List<Execution> results = new ArrayList<>(calls.size());
            for (ToolCall call : calls) {
                results.add(executeOne(call, config, ctx));
            }
            return results;
        }
        List<Future<Execution>> futures = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            futures.add(toolExecutor.submit(() -> executeOne(call, config, ctx)));
        }
        List<Execution> results = new ArrayList<>(calls.size());
        for (Future<Execution> future : futures) {
            try {
                results.add(future.get(config.getToolTimeoutMs() + 2000L, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                results.add(timeoutExecution());
            } catch (Exception e) {
                results.add(new Execution(null, failure("工具执行异常：" + StrUtil.maxLength(e.getMessage(), 100)),
                        0L, false));
            }
        }
        return results;
    }

    private Execution executeOne(ToolCall call, Prd2ApiProperties.Agent config, Ctx ctx) {
        long start = System.currentTimeMillis();

        Risk risk = toolRegistry.riskOf(call.name());
        boolean cacheable = risk != Risk.READ;
        String idemKey = cacheable ? idempotentKey(call, ctx) : null;
        if (idemKey != null) {
            ToolResult cached = readIdempotent(idemKey);
            if (cached != null) {
                log.info("工具幂等命中，跳过重复执行 tool={}", call.name());
                return new Execution(call, cached, System.currentTimeMillis() - start, true);
            }
        }

        // 只读工具重跑无副作用，可安全重试；写/危险工具只执行一次，避免重复副作用
        int maxAttempt = risk == Risk.READ ? Math.max(0, config.getToolRetry()) : 0;
        ToolResult result = null;
        for (int attempt = 0; attempt <= maxAttempt; attempt++) {
            result = invokeWithTimeout(call, config, ctx);
            if (result.success()) {
                break;
            }
            if (attempt < maxAttempt) {
                log.warn("工具执行失败，准备重试 tool={} attempt={}/{} msg={}",
                        call.name(), attempt + 1, maxAttempt, result.summary());
            }
        }
        long latency = System.currentTimeMillis() - start;
        metricUtil.agentTool(call.name(), risk.name(), result.success(), latency);
        if (idemKey != null && result.success()) {
            writeIdempotent(idemKey, result);
        }
        return new Execution(call, result, latency, false);
    }

    /**
     * 在独立线程内执行，并显式恢复租户上下文，超时则放弃等待
     */
    private ToolResult invokeWithTimeout(ToolCall call, Prd2ApiProperties.Agent config, Ctx ctx) {
        Future<ToolResult> future = toolExecutor.submit(() -> {
            TenantContextUtil.set(ctx.userId(), ctx.tenantId(), ctx.userKey(), ctx.role());
            try {
                return toolRegistry.execute(call.name(), call.arguments());
            } finally {
                TenantContextUtil.clear();
            }
        });
        try {
            return future.get(Math.max(500L, config.getToolTimeoutMs()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("工具执行超时 tool={} timeout={}ms", call.name(), config.getToolTimeoutMs());
            return failure("工具执行超时，请稍后重试");
        } catch (Exception e) {
            return failure("工具执行异常：" + StrUtil.maxLength(e.getMessage(), 100));
        }
    }

    /**
     * 幂等键：租户 + 用户 + 工具名 + 参数摘要，保证同一请求重复执行不产生二次副作用
     */
    private String idempotentKey(ToolCall call, Ctx ctx) {
        String raw = ctx.tenantId() + "|" + ctx.userKey()
                + "|" + call.name() + "|" + StrUtil.blankToDefault(call.arguments(), "{}");
        return RedisKeyConstant.ASSISTANT_TOOL_IDEM_PREFIX + DigestUtil.md5Hex(raw);
    }

    private ToolResult readIdempotent(String key) {
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            return StrUtil.isBlank(cached) ? null : objectMapper.readValue(cached, ToolResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeIdempotent(String key, ToolResult result) {
        try {
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(result),
                    IDEM_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("工具幂等结果写入失败 msg={}", e.getMessage());
        }
    }

    private Execution timeoutExecution() {
        return new Execution(null, failure("工具执行超时，请稍后重试"), 0L, false);
    }

    private ToolResult failure(String summary) {
        return new ToolResult(false, summary, null, "NONE", null, null);
    }

    /**
     * 调用线程捕获的租户上下文快照
     */
    private record Ctx(String tenantId, String userId, String userKey, String role) {
    }

    /**
     * 单次工具执行结果
     */
    public record Execution(ToolCall call, ToolResult result, long latencyMs, boolean idempotentHit) {
    }
}
