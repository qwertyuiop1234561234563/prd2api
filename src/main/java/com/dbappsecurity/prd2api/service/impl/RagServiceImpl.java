package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.rag.ChromaVectorService;
import com.dbappsecurity.prd2api.rag.EmbeddingService;
import com.dbappsecurity.prd2api.rag.RagRetrieveService;
import com.dbappsecurity.prd2api.service.RagService;
import com.dbappsecurity.prd2api.util.ChunkUtil;
import com.dbappsecurity.prd2api.vo.RagChunkVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 实现：分块 -> 向量化（带缓存）-> 任务级 collection 写入 -> 召回
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagServiceImpl implements RagService {

    private final ChromaVectorService chromaVectorService;
    private final RagRetrieveService ragRetrieveService;
    private final EmbeddingService embeddingService;
    private final Prd2ApiProperties properties;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void buildTaskIndex(String tenantId, String taskId, String text, String chunkStrategy) {
        String strategy = StrUtil.blankToDefault(chunkStrategy, properties.getChunk().getStrategy());
        List<String> chunks = ChunkUtil.chunk(text, strategy,
                properties.getChunk().getFixedSize(), properties.getChunk().getOverlap());
        if (chunks.isEmpty()) {
            log.warn("文档解析后无有效分块 taskId={}", taskId);
            return;
        }
        // 原文分块落缓存：既服务本地降级召回，也避免重复分块
        ragRetrieveService.cacheChunks(taskId, chunks);
        log.info("文档分块完成 taskId={} strategy={} chunks={}", taskId, strategy, chunks.size());

        if (!properties.getRag().isEnable()) {
            log.info("RAG 已关闭，跳过向量索引 taskId={}", taskId);
            return;
        }
        if (StrUtil.isBlank(properties.getLlm().getApiKey())) {
            log.warn("未配置 Embedding API Key，使用本地相似度召回 taskId={}", taskId);
            return;
        }
        try {
            ChromaVectorService.CollectionRef collection =
                    chromaVectorService.ensureCollection(tenantId, taskId);
            List<String> ids = new ArrayList<>(chunks.size());
            List<float[]> embeddings = new ArrayList<>(chunks.size());
            List<String> sources = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                ids.add(ChunkUtil.md5(chunk));
                sources.add("原文分块#" + (i + 1));
                embeddings.add(embeddingService.embed(chunk));
            }
            chromaVectorService.addChunks(collection.id(), ids, embeddings, chunks, sources);
        } catch (Exception e) {
            // 向量索引失败不阻断任务，召回自动降级为本地相似度
            log.warn("构建任务向量索引失败，自动降级本地召回 taskId={} msg={}", taskId, e.getMessage());
        }
    }

    @Override
    public List<RagChunkVO> retrieve(String taskId, String query, int topK) {
        return ragRetrieveService.retrieve(taskId, query, topK);
    }

    @Override
    public void dropTaskIndex(String taskId) {
        try {
            ChromaVectorService.CollectionRef collection = chromaVectorService.cachedCollection(taskId);
            if (collection != null) {
                chromaVectorService.deleteCollection(collection);
            }
            stringRedisTemplate.delete(RedisKeyConstant.ragCollection(taskId));
            ragRetrieveService.evictChunks(taskId);
        } catch (Exception e) {
            log.warn("清理任务向量索引失败 taskId={}", taskId, e);
        }
    }

    @Override
    public boolean chromaAvailable() {
        return properties.getRag().isEnable() && chromaVectorService.heartbeat();
    }
}
