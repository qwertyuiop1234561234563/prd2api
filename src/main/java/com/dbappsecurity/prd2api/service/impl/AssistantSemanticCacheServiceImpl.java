package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.rag.EmbeddingService;
import com.dbappsecurity.prd2api.service.AssistantAgentService;
import com.dbappsecurity.prd2api.service.AssistantSemanticCacheService;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.AgentStepVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 语义缓存实现：Redis Hash 存条目（含问题向量），用余弦相似度判定命中
 * <p>
 * 单用户条目上限默认 100，按写入时间淘汰最老条目；向量复用 EmbeddingService 的 Redis 缓存，
 * 同一问题二次写入不再产生额外 embedding 调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantSemanticCacheServiceImpl implements AssistantSemanticCacheService {

    private static final String[] CONTEXT_PREFIXES = {
            "那", "那么", "这", "它", "上面", "刚才", "继续", "再说", "还有", "然后", "这个", "那个"};

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final Prd2ApiProperties properties;
    private final MetricUtil metricUtil;

    @Override
    public Entry lookup(String question) {
        if (!enabled() || !selfContained(question)) {
            return null;
        }
        float[] vector = embed(question);
        if (vector.length == 0) {
            return null;
        }
        Map<Object, Object> entries = entries(redisKey());
        if (entries.isEmpty()) {
            metricUtil.agentCache(false);
            return null;
        }
        double threshold = properties.getAgent().getSemanticCacheThreshold();
        Entry best = null;
        double bestScore = 0.0D;
        for (Object value : entries.values()) {
            EntryPayload payload = parse(String.valueOf(value));
            if (payload == null || payload.vector() == null) {
                continue;
            }
            double score = cosine(vector, payload.vector());
            if (score > bestScore) {
                bestScore = score;
                best = new Entry(payload.reply(), payload.mode(), payload.intent(), payload.steps(), score);
            }
        }
        boolean hit = best != null && bestScore >= threshold;
        metricUtil.agentCache(hit);
        if (hit) {
            log.info("语义缓存命中 score={} threshold={} question={}",
                    String.format("%.4f", bestScore), threshold, StrUtil.maxLength(question, 40));
            return new Entry(best.reply(), best.mode(), best.intent(), best.steps(), bestScore);
        }
        return null;
    }

    @Override
    public boolean cacheable(String question, AssistantAgentService.AgentOutcome outcome, String intentCode) {
        if (!enabled() || outcome == null || !selfContained(question)) {
            return false;
        }
        String reply = outcome.reply();
        if (StrUtil.isBlank(reply) || reply.length() < 16) {
            return false;
        }
        String mode = StrUtil.blankToDefault(outcome.mode(), "");
        if (!"AGENT".equals(mode) && !"LLM".equals(mode) && !"FAQ".equals(mode)) {
            return false;
        }
        if (StrUtil.isNotBlank(outcome.actionType()) && !"NONE".equals(outcome.actionType())) {
            return false;
        }
        if (reply.contains("确认") || reply.contains("我帮你查")) {
            return false;
        }
        if (outcome.steps() != null) {
            for (AgentStepVO step : outcome.steps()) {
                String type = StrUtil.blankToDefault(step.getType(), "");
                // 中断、待确认、记忆写入等过程性步骤，以及任何失败步骤，都不可作为可复用答复
                if ("TOOL".equals(type) || "CONFIRM".equals(type) || "CANCEL".equals(type)
                        || "MEMORY".equals(type) || "GUARD".equals(type)) {
                    return false;
                }
                if (!step.isSuccess()) {
                    return false;
                }
            }
        }
        return !"CONFIRM".equals(StrUtil.blankToDefault(intentCode, ""));
    }

    @Override
    public void put(String question, String reply, String mode, String intentCode, List<AgentStepVO> steps) {
        if (!enabled() || StrUtil.isBlank(question) || StrUtil.isBlank(reply)) {
            return;
        }
        try {
            float[] vector = embeddingService.embed(question);
            if (vector.length == 0) {
                return;
            }
            String key = redisKey();
            String field = DigestUtil.md5Hex(normalize(question));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("q", StrUtil.maxLength(question, 200));
            payload.put("reply", StrUtil.maxLength(reply, 2000));
            payload.put("mode", StrUtil.blankToDefault(mode, "AGENT"));
            payload.put("intent", StrUtil.blankToDefault(intentCode, ""));
            payload.put("steps", steps == null ? List.of() : steps);
            payload.put("vector", vector);
            payload.put("ts", System.currentTimeMillis());
            stringRedisTemplate.opsForHash().put(key, field, objectMapper.writeValueAsString(payload));
            stringRedisTemplate.expire(key, Math.max(60, properties.getAgent().getSemanticCacheTtlSeconds()),
                    TimeUnit.SECONDS);
            trim(key);
        } catch (Exception e) {
            // 缓存是纯优化路径，任何异常都不能影响正常问答
            log.warn("语义缓存写入失败 msg={}", e.getMessage());
        }
    }

    /**
     * 条目超上限时按写入时间淘汰最老记录
     */
    private void trim(String key) {
        int max = Math.max(10, properties.getAgent().getSemanticCacheMaxEntries());
        Map<Object, Object> entries = entries(key);
        if (entries.size() <= max) {
            return;
        }
        List<Map.Entry<Object, Object>> sorted = new ArrayList<>(entries.entrySet());
        sorted.sort(Comparator.comparingLong(entry -> timestamp(String.valueOf(entry.getValue()))));
        int overflow = entries.size() - max;
        Object[] fields = sorted.subList(0, overflow).stream().map(Map.Entry::getKey).toArray();
        stringRedisTemplate.opsForHash().delete(key, fields);
    }

    private Map<Object, Object> entries(String key) {
        try {
            return stringRedisTemplate.opsForHash().entries(key);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private long timestamp(String json) {
        EntryPayload payload = parse(json);
        return payload == null ? 0L : payload.ts();
    }

    private EntryPayload parse(String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            List<AgentStepVO> steps = map.get("steps") == null ? List.of()
                    : objectMapper.convertValue(map.get("steps"), new TypeReference<List<AgentStepVO>>() {
                    });
            float[] vector = map.get("vector") == null ? new float[0]
                    : objectMapper.convertValue(map.get("vector"), float[].class);
            return new EntryPayload(String.valueOf(map.getOrDefault("reply", "")),
                    String.valueOf(map.getOrDefault("mode", "AGENT")),
                    String.valueOf(map.getOrDefault("intent", "")),
                    steps, vector, ((Number) map.getOrDefault("ts", 0L)).longValue());
        } catch (Exception e) {
            return null;
        }
    }

    private float[] embed(String question) {
        try {
            return embeddingService.embed(question);
        } catch (Exception e) {
            log.warn("语义缓存 embedding 失败，跳过缓存 msg={}", e.getMessage());
            return new float[0];
        }
    }

    /**
     * 余弦相似度
     */
    private double cosine(float[] a, float[] b) {
        if (a.length == 0 || a.length != b.length) {
            return 0.0D;
        }
        double dot = 0.0D;
        double normA = 0.0D;
        double normB = 0.0D;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA <= 0 || normB <= 0) {
            return 0.0D;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 只缓存可独立成立的问题：过短、指代上文、带任务 ID 的一律跳过
     */
    private boolean selfContained(String question) {
        String compact = StrUtil.blankToDefault(question, "").replaceAll("\\s+", "");
        if (compact.length() < 6) {
            return false;
        }
        for (String prefix : CONTEXT_PREFIXES) {
            if (compact.startsWith(prefix)) {
                return false;
            }
        }
        if (compact.contains("上面") || compact.contains("刚才") || compact.contains("前面")) {
            return false;
        }
        return !compact.matches(".*[0-9a-fA-F]{32}.*");
    }

    private String normalize(String question) {
        return StrUtil.blankToDefault(question, "").replaceAll("\\s+", "")
                .replaceAll("[。！？!?，,；;、]+$", "").toLowerCase();
    }

    private boolean enabled() {
        Prd2ApiProperties.Agent config = properties.getAgent();
        return config != null && config.isSemanticCache()
                && StrUtil.isNotBlank(properties.getLlm().getApiKey());
    }

    private String redisKey() {
        String tenantId = TenantContextUtil.getTenantId();
        String userKey = StrUtil.blankToDefault(TenantContextUtil.getUserKey(),
                TenantContextUtil.getUserId());
        return RedisKeyConstant.ASSISTANT_SEMCACHE_PREFIX + StrUtil.blankToDefault(tenantId, "-")
                + ":" + StrUtil.blankToDefault(userKey, "-");
    }

    /**
     * 缓存条目载荷
     */
    private record EntryPayload(String reply, String mode, String intent,
                                List<AgentStepVO> steps, float[] vector, long ts) {
    }
}
