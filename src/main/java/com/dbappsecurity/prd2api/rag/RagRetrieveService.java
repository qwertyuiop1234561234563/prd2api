package com.dbappsecurity.prd2api.rag;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.util.SimilarityUtil;
import com.dbappsecurity.prd2api.vo.RagChunkVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * RAG 召回服务
 * <p>
 * 优先走向量库；向量库 / Embedding 不可用时自动降级为本地 bigram 相似度召回，保证任务不中断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrieveService {

    private static final long CHUNK_TTL_DAYS = 7L;

    private final EmbeddingService embeddingService;
    private final ChromaVectorService chromaVectorService;
    private final StringRedisTemplate stringRedisTemplate;
    private final Prd2ApiProperties properties;

    /**
     * 缓存任务分块原文（供本地降级召回）
     */
    public void cacheChunks(String taskId, List<String> chunks) {
        stringRedisTemplate.opsForValue()
                .set(RedisKeyConstant.ragChunks(taskId), JSONUtil.toJsonStr(chunks), CHUNK_TTL_DAYS, TimeUnit.DAYS);
    }

    public List<String> readChunks(String taskId) {
        String json = stringRedisTemplate.opsForValue().get(RedisKeyConstant.ragChunks(taskId));
        if (StrUtil.isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            return JSONUtil.toList(json, String.class);
        } catch (Exception e) {
            log.warn("分块缓存解析失败 taskId={}", taskId, e);
            return new ArrayList<>();
        }
    }

    public void evictChunks(String taskId) {
        stringRedisTemplate.delete(RedisKeyConstant.ragChunks(taskId));
    }

    /**
     * 召回 TopK 片段，向量库异常时自动降级
     */
    public List<RagChunkVO> retrieve(String taskId, String query, int topK) {
        int k = topK > 0 ? topK : properties.getRag().getTopK();
        List<String> chunks = readChunks(taskId);
        if (!properties.getRag().isEnable() || StrUtil.isBlank(properties.getLlm().getApiKey())) {
            return localRetrieve(chunks, query, k);
        }
        try {
            ChromaVectorService.CollectionRef collection = chromaVectorService.cachedCollection(taskId);
            if (collection == null) {
                return localRetrieve(chunks, query, k);
            }
            List<RagChunkVO> vectorResult = chromaVectorService.query(
                    collection.id(), embeddingService.embed(query), k);
            if (vectorResult.isEmpty()) {
                return localRetrieve(chunks, query, k);
            }
            return vectorResult;
        } catch (Exception e) {
            log.warn("向量召回失败，降级本地召回 taskId={} msg={}", taskId, e.getMessage());
            return localRetrieve(chunks, query, k);
        }
    }

    /**
     * 本地降级召回：bigram 相似度排序，保证无向量库也能跑通流水线
     */
    public List<RagChunkVO> localRetrieve(List<String> chunks, String query, int topK) {
        if (chunks == null || chunks.isEmpty()) {
            return new ArrayList<>();
        }
        if (StrUtil.isBlank(query)) {
            List<RagChunkVO> head = new ArrayList<>();
            for (int i = 0; i < Math.min(topK, chunks.size()); i++) {
                head.add(new RagChunkVO(chunks.get(i), "本地分块#" + (i + 1), 1.0D));
            }
            return head;
        }
        List<RagChunkVO> scored = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            double score = SimilarityUtil.textSimilarity(query, chunks.get(i));
            scored.add(new RagChunkVO(chunks.get(i), "本地分块#" + (i + 1), score));
        }
        scored.sort(Comparator.comparingDouble(RagChunkVO::getScore).reversed());
        return scored.size() > topK ? new ArrayList<>(scored.subList(0, topK)) : scored;
    }
}
