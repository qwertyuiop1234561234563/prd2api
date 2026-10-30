package com.dbappsecurity.prd2api.rag;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.AssistantKnowledgeConstant;
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
 * 助手知识库：平台文档的向量化索引与检索
 * <p>
 * 首次检索时懒加载建索引（Chroma + Embedding 可用时），并在 Redis 打标记避免重复写入；
 * 向量链路异常自动降级为本地 bigram 相似度召回，保证无 Chroma 环境也能回答平台问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private static final long INDEX_MARKER_TTL_DAYS = 7L;

    private final EmbeddingService embeddingService;
    private final ChromaVectorService chromaVectorService;
    private final StringRedisTemplate stringRedisTemplate;
    private final Prd2ApiProperties properties;

    private volatile boolean indexChecked = false;
    private volatile ChromaVectorService.CollectionRef collectionRef;

    /**
     * 检索平台知识，返回片段与来源
     */
    public List<RagChunkVO> search(String query, int topK) {
        if (StrUtil.isBlank(query)) {
            return List.of();
        }
        int k = topK > 0 ? topK : Math.max(1, properties.getAgent().getKnowledgeTopK());
        ensureIndexed();
        if (collectionRef != null) {
            try {
                List<RagChunkVO> vectorResult = chromaVectorService.query(
                        collectionRef.id(), embeddingService.embed(query), k);
                if (!vectorResult.isEmpty()) {
                    return vectorResult;
                }
            } catch (Exception e) {
                log.warn("知识库向量召回失败，降级本地召回 msg={}", e.getMessage());
            }
        }
        return localSearch(query, k);
    }

    /**
     * 文档条数，用于能力清单展示
     */
    public int docCount() {
        return AssistantKnowledgeConstant.DOCS.size();
    }

    /**
     * 向量检索是否可用（Chroma + Embedding 均已配置）
     */
    public boolean vectorEnabled() {
        return properties.getRag().isEnable() && StrUtil.isNotBlank(properties.getLlm().getApiKey());
    }

    /**
     * 懒加载索引：只在首次检索时触发，失败不阻断对话
     */
    private void ensureIndexed() {
        if (indexChecked) {
            return;
        }
        synchronized (this) {
            if (indexChecked) {
                return;
            }
            indexChecked = true;
            if (!vectorEnabled()) {
                return;
            }
            try {
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeyConstant.ASSISTANT_KB_INDEXED))) {
                    collectionRef = chromaVectorService.ensureNamedCollection(AssistantKnowledgeConstant.COLLECTION_NAME);
                    return;
                }
                ChromaVectorService.CollectionRef ref =
                        chromaVectorService.ensureNamedCollection(AssistantKnowledgeConstant.COLLECTION_NAME);
                List<AssistantKnowledgeConstant.Doc> docs = AssistantKnowledgeConstant.DOCS;
                List<String> ids = new ArrayList<>(docs.size());
                List<String> contents = new ArrayList<>(docs.size());
                List<String> sources = new ArrayList<>(docs.size());
                List<float[]> embeddings = new ArrayList<>(docs.size());
                for (AssistantKnowledgeConstant.Doc doc : docs) {
                    ids.add("kb-" + doc.id());
                    contents.add(doc.content());
                    sources.add(doc.title());
                    embeddings.add(embeddingService.embed(doc.content()));
                }
                chromaVectorService.addChunks(ref.id(), ids, embeddings, contents, sources);
                stringRedisTemplate.opsForValue().set(RedisKeyConstant.ASSISTANT_KB_INDEXED, "1",
                        INDEX_MARKER_TTL_DAYS, TimeUnit.DAYS);
                collectionRef = ref;
                log.info("助手知识库索引完成 docs={} collection={}", docs.size(), ref.name());
            } catch (Exception e) {
                log.warn("助手知识库索引失败，后续走本地召回 msg={}", e.getMessage());
                collectionRef = null;
            }
        }
    }

    /**
     * 本地降级召回：bigram 相似度排序
     */
    private List<RagChunkVO> localSearch(String query, int topK) {
        List<RagChunkVO> scored = new ArrayList<>();
        for (AssistantKnowledgeConstant.Doc doc : AssistantKnowledgeConstant.DOCS) {
            double score = SimilarityUtil.textSimilarity(query, doc.title() + doc.content());
            scored.add(new RagChunkVO(doc.content(), doc.title(), score));
        }
        scored.sort(Comparator.comparingDouble(RagChunkVO::getScore).reversed());
        return scored.size() > topK ? new ArrayList<>(scored.subList(0, topK)) : scored;
    }
}
