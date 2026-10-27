package com.dbappsecurity.prd2api.rag;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Embedding 服务：OpenAI 兼容接口，chunk md5 做 key 缓存向量，避免重复调用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long CACHE_TTL_DAYS = 7L;

    private final OkHttpClient okHttpClient;
    private final Prd2ApiProperties properties;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final MetricUtil metricUtil;

    /**
     * 文本向量化，结果缓存到 Redis
     */
    public float[] embed(String text) {
        if (StrUtil.isBlank(text)) {
            return new float[0];
        }
        String md5 = DigestUtil.md5Hex(text);
        String cacheKey = RedisKeyConstant.ragEmbedding(md5);
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            try {
                return parseVector(cached);
            } catch (Exception e) {
                log.warn("向量缓存解析失败，重新请求 embedding cacheKey={}", cacheKey);
            }
        }

        long start = System.currentTimeMillis();
        String responseBody = postEmbedding(text);
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddingNode = root.path("data").path(0).path("embedding");
            if (!embeddingNode.isArray() || embeddingNode.isEmpty()) {
                throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "Embedding 返回为空");
            }
            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(vector),
                    CACHE_TTL_DAYS, TimeUnit.DAYS);
            metricUtil.ragLatency(System.currentTimeMillis() - start);
            return vector;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "Embedding 解析失败: " + e.getMessage(), e);
        }
    }

    private String postEmbedding(String text) {
        Prd2ApiProperties.Llm llm = properties.getLlm();
        String url = StrUtil.removeSuffix(llm.getBaseUrl(), "/") + "/embeddings";
        String body;
        try {
            body = objectMapper.writeValueAsString(java.util.Map.of(
                    "model", llm.getEmbeddingModel(),
                    "input", text));
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.SYSTEM_ERROR, "Embedding 请求体构造失败", e);
        }
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + llm.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new BizException(BizErrorCodeEnum.BIZ_ERROR,
                        "Embedding 调用失败 http=" + response.code() + " body=" + StrUtil.maxLength(responseBody, 300));
            }
            return responseBody;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "Embedding 调用异常: " + e.getMessage(), e);
        }
    }

    private float[] parseVector(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        float[] vector = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            vector[i] = (float) node.get(i).asDouble();
        }
        return vector;
    }

    /**
     * 批量向量化（单条失败不影响其它片段）
     */
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(embed(text));
        }
        return result;
    }
}
