package com.dbappsecurity.prd2api.agent.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolRegistry.Risk;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolRegistry.ToolResult;
import com.dbappsecurity.prd2api.config.AsyncThreadPoolConfig;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.service.AssistantHotCacheService;
import com.dbappsecurity.prd2api.service.AssistantToolCircuitService;
import com.dbappsecurity.prd2api.service.LlmInvokeService.ToolCall;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.RateLimitUtil;
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
    private final AssistantArgumentGuard argumentGuard;
    private final AssistantToolCircuitService circuitService;
    private final Prd2ApiProperties properties;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final MetricUtil metricUtil;
    private final RateLimitUtil rateLimitUtil;
    private final AssistantHotCacheService hotCacheService;
    private final ThreadPoolTaskExecutor toolExecutor;

    public AssistantToolExecutor(AssistantToolRegistry toolRegistry,
                                 AssistantArgumentGuard argumentGuard,
                                 AssistantToolCircuitService circuitService,
                                 Prd2ApiProperties properties,
                                 StringRedisTemplate stringRedisTemplate,
                                 ObjectMapper objectMapper,
                                 MetricUtil metricUtil,
                                 RateLimitUtil rateLimitUtil,
                                 AssistantHotCacheService hotCacheService,
                                 @Qualifier(AsyncThreadPoolConfig.TOOL_EXECUTOR) ThreadPoolTaskExecutor toolExecutor) {
        this.toolRegistry = toolRegistry;
        this.argumentGuard = argumentGuard;
        this.circuitService = circuitService;
        this.properties = properties;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.metricUtil = metricUtil;
        this.rateLimitUtil = rateLimitUtil;
        this.hotCacheService = hotCacheService;
        this.toolExecutor = toolExecutor;
    }

    /**
     * 并行执行一批工具调用，返回顺序与模型给出的调用顺序一致
     * <p>
     * 关键修正：每个工具只在工具线程池提交一次，超时由调用线程 future.get 控制。
     * 之前在工作线程里再向同一个线程池提交子任务，池被占满时会互相等待直至全部超时。
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
                results.add(submitAndWait(call, config, ctx));
            }
            return results;
        }
        List<Future<Execution>> futures = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            futures.add(toolExecutor.submit(() -> {
                TenantContextUtil.set(ctx.userId(), ctx.tenantId(), ctx.userKey(), ctx.role());
                try {
                    return executeOne(call, config, ctx);
                } finally {
                    TenantContextUtil.clear();
                }
            }));
        }
        long timeout = Math.max(500L, config.getToolTimeoutMs()) + 1000L;
        List<Execution> results = new ArrayList<>(calls.size());
        for (int i = 0; i < futures.size(); i++) {
            ToolCall call = calls.get(i);
            try {
                results.add(futures.get(i).get(timeout, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
                log.warn("工具执行超时 tool={} timeout={}ms", call.name(), timeout);
                results.add(new Execution(call, failure("工具执行超时，请稍后重试"), 0L, false, false));
            } catch (Exception e) {
                results.add(new Execution(call,
                        failure("工具执行异常：" + StrUtil.maxLength(e.getMessage(), 100)), 0L, false, false));
            }
        }
        return results;
    }

    /**
     * 单个工具提交到工具线程池并等待结果，保证调用线程不阻塞在业务查询本身
     */
    private Execution submitAndWait(ToolCall call, Prd2ApiProperties.Agent config, Ctx ctx) {
        Future<Execution> future = toolExecutor.submit(() -> {
            TenantContextUtil.set(ctx.userId(), ctx.tenantId(), ctx.userKey(), ctx.role());
            try {
                return executeOne(call, config, ctx);
            } finally {
                TenantContextUtil.clear();
            }
        });
        try {
            return future.get(Math.max(500L, config.getToolTimeoutMs()) + 1000L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("工具执行超时 tool={} timeout={}ms", call.name(), config.getToolTimeoutMs());
            return new Execution(call, failure("工具执行超时，请稍后重试"), 0L, false, false);
        } catch (Exception e) {
            return new Execution(call,
                    failure("工具执行异常：" + StrUtil.maxLength(e.getMessage(), 100)), 0L, false, false);
        }
    }

    private Execution executeOne(ToolCall call, Prd2ApiProperties.Agent config, Ctx ctx) {
        long start = System.currentTimeMillis();

        // 入参守卫：模型生成的参数同样不可信，非法参数在执行前直接拒绝（零副作用）
        AssistantArgumentGuard.Verdict verdict = argumentGuard.check(call.name(), call.arguments());
        if (!verdict.ok()) {
            metricUtil.agentArgGuard(false);
            log.warn("工具参数被安全守卫拒绝 tool={} reason={}", call.name(), verdict.reason());
            return new Execution(call, failure("参数校验未通过：" + verdict.reason()), 0L, false, false);
        }
        metricUtil.agentArgGuard(true);

        Risk risk = toolRegistry.riskOf(call.name());
        boolean cacheable = risk != Risk.READ;
        String idemKey = cacheable ? idempotentKey(call, ctx) : null;
        if (idemKey != null) {
            ToolResult cached = readIdempotent(idemKey);
            if (cached != null) {
                log.info("工具幂等命中，跳过重复执行 tool={}", call.name());
                return new Execution(call, cached, System.currentTimeMillis() - start, true, false);
            }
        }

        // 只读工具的短 TTL 结果缓存：连续几轮或回放时同参数直接复用，降低延迟与外部接口压力
        String cacheKey = risk == Risk.READ && config.isToolCacheEnabled() ? readCacheKey(call, ctx) : null;
        if (cacheKey != null) {
            // 一级缓存：热点参数直接命中 JVM 本地，减少高频重复读 Redis
            ToolResult local = hotCacheService.get(cacheKey);
            if (local != null) {
                metricUtil.agentToolCache(true);
                log.info("只读工具 L1 缓存命中 tool={}", call.name());
                return new Execution(call, local, System.currentTimeMillis() - start, false, true);
            }
            ToolResult cached = readCache(cacheKey);
            if (cached != null) {
                metricUtil.agentToolCache(true);
                hotCacheService.put(cacheKey, cached, localTtlMillis(config));
                log.info("只读工具结果缓存命中 tool={}", call.name());
                return new Execution(call, cached, System.currentTimeMillis() - start, false, true);
            }
            metricUtil.agentToolCache(false);
            // 缓存击穿保护：同一 key 并发未命中时只放一个线程回源，其余线程复用结果
            long waitStart = System.currentTimeMillis();
            AssistantHotCacheService.Loaded<ToolResult> loaded = hotCacheService.loadOnce(cacheKey,
                    () -> loadReadThrough(call, config, ctx, start, cacheKey));
            return new Execution(call, loaded.value(), System.currentTimeMillis() - waitStart,
                    false, loaded.hit());
        }
        return executeDownstream(call, config, ctx, start);
    }

    /**
     * 单飞领先线程的回源动作：真正执行工具并把结果写回 Redis 与本地一级缓存
     */
    private ToolResult loadReadThrough(ToolCall call, Prd2ApiProperties.Agent config,
                                       Ctx ctx, long start, String cacheKey) {
        Execution execution = executeDownstream(call, config, ctx, start);
        ToolResult result = execution.result();
        if (result != null && result.success()) {
            writeCache(cacheKey, result);
            hotCacheService.put(cacheKey, result, localTtlMillis(config));
        }
        return result;
    }

    /**
     * 真正触达下游的执行：熔断 → 限流 → 重试 → 指标 → 幂等/缓存落盘
     * <p>
     * 抽出独立方法后，只读路径可在缓存未命中时用单飞包住这一段，避免并发穿透到下游。
     */
    private Execution executeDownstream(ToolCall call, Prd2ApiProperties.Agent config,
                                        Ctx ctx, long start) {
        Risk risk = toolRegistry.riskOf(call.name());
        String idemKey = risk != Risk.READ ? idempotentKey(call, ctx) : null;

        // 依赖熔断：命中缓存/幂等时不触碰下游，因此熔断检查放在缓存之后；熔断中的工具直接快速失败
        AssistantToolCircuitService.Decision circuit = circuitService.allow(call.name());
        if (!circuit.allowed()) {
            log.warn("工具已熔断，快速失败 tool={} state={} reason={}",
                    call.name(), circuit.state(), circuit.reason());
            return new Execution(call, failure("工具暂时不可用：" + circuit.reason()),
                    System.currentTimeMillis() - start, false, false);
        }

        // 工具级限流：缓存命中不消耗下游资源，因此只对“真正要执行”的调用计数
        int rateLimit = config.getToolRateLimitPerMinute();
        if (rateLimit > 0) {
            String rateKey = RedisKeyConstant.limitAssistantTool(ctx.tenantId(), ctx.userKey(), call.name());
            if (!rateLimitUtil.allowed(rateKey, rateLimit)) {
                metricUtil.agentToolRateLimit(false);
                log.warn("工具调用触发限流 tool={} limit={}/min", call.name(), rateLimit);
                return new Execution(call, failure("工具调用过于频繁（每用户每分钟上限 " + rateLimit
                        + " 次），请稍后再试"), System.currentTimeMillis() - start, false, false);
            }
            metricUtil.agentToolRateLimit(true);
        }

        // 只读工具重跑无副作用，可安全重试；写/危险工具只执行一次，避免重复副作用
        int maxAttempt = risk == Risk.READ ? Math.max(0, config.getToolRetry()) : 0;
        ToolResult result = null;
        for (int attempt = 0; attempt <= maxAttempt; attempt++) {
            result = toolRegistry.execute(call.name(), call.arguments());
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
        return new Execution(call, result, latency, false, false);
    }

    /**
     * L1 本地缓存存活时间：取本地 TTL 与 Redis TTL 的较小值，保证本地不会比 Redis 更旧
     */
    private long localTtlMillis(Prd2ApiProperties.Agent config) {
        long redisTtl = config == null || config.getToolCacheTtlSeconds() <= 0
                ? 30L : config.getToolCacheTtlSeconds();
        long localTtl = config == null ? 0L : config.getHotCacheTtlSeconds();
        if (localTtl <= 0) {
            return 0L;
        }
        return Math.min(redisTtl, localTtl) * 1000L;
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

    /**
     * 只读缓存键：租户 + 用户 + 工具名 + 参数摘要，保证不同用户/不同参数互不串用
     */
    private String readCacheKey(ToolCall call, Ctx ctx) {
        String raw = ctx.tenantId() + "|" + ctx.userKey() + "|" + call.name()
                + "|" + StrUtil.blankToDefault(call.arguments(), "{}");
        return RedisKeyConstant.ASSISTANT_TOOL_CACHE_PREFIX + DigestUtil.md5Hex(raw);
    }

    private ToolResult readCache(String key) {
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            return StrUtil.isBlank(cached) ? null : objectMapper.readValue(cached, ToolResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(String key, ToolResult result) {
        try {
            Prd2ApiProperties.Agent config = properties.getAgent();
            long ttl = config == null || config.getToolCacheTtlSeconds() <= 0
                    ? 30L : config.getToolCacheTtlSeconds();
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(result),
                    ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("只读工具缓存写入失败 msg={}", e.getMessage());
        }
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
    public record Execution(ToolCall call, ToolResult result, long latencyMs, boolean idempotentHit,
                            boolean cacheHit) {
    }
}
