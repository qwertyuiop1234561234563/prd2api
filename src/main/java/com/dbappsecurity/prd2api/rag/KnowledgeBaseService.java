package com.dbappsecurity.prd2api.rag;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.AssistantKnowledgeConstant;
import com.dbappsecurity.prd2api.constant.KnowledgeDocStatus;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.entity.AssistantKnowledgeChunk;
import com.dbappsecurity.prd2api.entity.AssistantKnowledgeDoc;
import com.dbappsecurity.prd2api.mapper.AssistantKnowledgeChunkMapper;
import com.dbappsecurity.prd2api.mapper.AssistantKnowledgeDocMapper;
import com.dbappsecurity.prd2api.util.SimilarityUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.RagChunkVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 助手知识库：内置平台语料 + 租户离线上传文档的统一检索入口
 * <p>
 * 内置语料首次检索时懒加载写入全局 Chroma collection；
 * 租户上传文档写入租户独立 collection（租户隔离），向量链路不可用时自动降级为本地 bigram 召回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private static final long INDEX_MARKER_TTL_DAYS = 7L;
    /** 租户知识库 collection 前缀，与内置语料、任务级 collection 均隔离 */
    private static final String TENANT_COLLECTION_PREFIX = "prd2api-assistant-kb-";

    private final EmbeddingService embeddingService;
    private final ChromaVectorService chromaVectorService;
    private final StringRedisTemplate stringRedisTemplate;
    private final Prd2ApiProperties properties;
    private final AssistantKnowledgeDocMapper docMapper;
    private final AssistantKnowledgeChunkMapper chunkMapper;

    /** 租户 collection 引用缓存，避免每次检索都向 Chroma 发起创建请求 */
    private final Map<String, ChromaVectorService.CollectionRef> tenantCollections = new ConcurrentHashMap<>();

    private volatile boolean indexChecked = false;
    private volatile ChromaVectorService.CollectionRef collectionRef;

    /**
     * 检索知识：内置语料与租户上传文档合并排序，返回片段与来源
     */
    public List<RagChunkVO> search(String query, int topK) {
        if (StrUtil.isBlank(query)) {
            return List.of();
        }
        int k = topK > 0 ? topK : Math.max(1, properties.getAgent().getKnowledgeTopK());
        List<RagChunkVO> merged = new ArrayList<>();
        List<RagChunkVO> builtIn = searchBuiltIn(query, k);
        if (builtIn != null) {
            merged.addAll(builtIn);
        }
        List<RagChunkVO> uploaded = searchUploaded(TenantContextUtil.getTenantId(), query, k);
        if (uploaded != null) {
            merged.addAll(uploaded);
        }
        return topK(merged, k);
    }

    /**
     * 知识库文档条数（内置 + 当前租户已完成的离线文档），用于能力清单展示
     */
    public int docCount() {
        int base = AssistantKnowledgeConstant.DOCS.size();
        String tenantId = TenantContextUtil.getTenantId();
        if (StrUtil.isBlank(tenantId)) {
            return base;
        }
        Long uploaded = docMapper.selectCount(new LambdaQueryWrapper<AssistantKnowledgeDoc>()
                .eq(AssistantKnowledgeDoc::getTenantId, tenantId)
                .eq(AssistantKnowledgeDoc::getStatus, KnowledgeDocStatus.COMPLETED.name()));
        return base + (uploaded == null ? 0 : uploaded.intValue());
    }

    /**
     * 向量检索是否可用（RAG 开关、Embedding 密钥与 Chroma 连通性三者都满足）
     * <p>
     * 连通性带短缓存，避免每次检索都探测一次 Chroma。
     */
    public boolean vectorEnabled() {
        return properties.getRag().isEnable()
                && StrUtil.isNotBlank(properties.getLlm().getApiKey())
                && chromaVectorService.available();
    }

    /**
     * 获取或创建租户知识库 collection（供索引任务写入 / 删除使用）
     */
    public ChromaVectorService.CollectionRef ensureTenantCollection(String tenantId) {
        return tenantCollections.computeIfAbsent(tenantId,
                tenant -> chromaVectorService.ensureNamedCollection(tenantCollectionName(tenant)));
    }

    /**
     * 清理 collection 引用缓存：文档删除或重建后调用，下次检索重新解析
     */
    public void evictTenantCollection(String tenantId) {
        if (!StrUtil.isBlank(tenantId)) {
            tenantCollections.remove(tenantId);
        }
    }

    /**
     * 租户 collection 名称：Chroma 限制 3~63 位，仅字母数字及 ._- ，首尾为字母数字
     */
    public static String tenantCollectionName(String tenantId) {
        String safeTenant = StrUtil.blankToDefault(tenantId, "default").toLowerCase()
                .replaceAll("[^a-z0-9._-]", "-");
        String name = TENANT_COLLECTION_PREFIX + safeTenant;
        if (name.length() > 63) {
            name = name.substring(name.length() - 63);
        }
        name = name.replaceAll("^[^a-z0-9]+", "").replaceAll("[^a-z0-9]+$", "");
        return name.length() < 3 ? "kb-" + safeTenant : name;
    }

    /**
     * 内置平台语料检索
     */
    private List<RagChunkVO> searchBuiltIn(String query, int topK) {
        ensureIndexed();
        if (collectionRef != null) {
            try {
                List<RagChunkVO> vectorResult = chromaVectorService.query(
                        collectionRef.id(), embeddingService.embed(query), topK);
                if (!vectorResult.isEmpty()) {
                    return vectorResult;
                }
            } catch (Exception e) {
                log.warn("知识库向量召回失败，降级本地召回 msg={}", e.getMessage());
            }
        }
        return localSearch(query, topK);
    }

    /**
     * 租户离线文档检索：优先向量召回，向量不可用时走本地分段相似度
     */
    private List<RagChunkVO> searchUploaded(String tenantId, String query, int topK) {
        if (StrUtil.isBlank(tenantId)) {
            return List.of();
        }
        Long completed = docMapper.selectCount(new LambdaQueryWrapper<AssistantKnowledgeDoc>()
                .eq(AssistantKnowledgeDoc::getTenantId, tenantId)
                .eq(AssistantKnowledgeDoc::getStatus, KnowledgeDocStatus.COMPLETED.name())
                .eq(AssistantKnowledgeDoc::getEnabled, 1));
        if (completed == null || completed == 0) {
            // 没有可检索文档时清掉缓存引用，避免空 collection 常驻内存
            tenantCollections.remove(tenantId);
            return List.of();
        }
        if (vectorEnabled()) {
            try {
                ChromaVectorService.CollectionRef ref = ensureTenantCollection(tenantId);
                List<RagChunkVO> hits = chromaVectorService.query(
                        ref.id(), embeddingService.embed(query), topK);
                if (!hits.isEmpty()) {
                    return hits;
                }
            } catch (Exception e) {
                log.warn("租户知识库向量召回失败，降级本地召回 tenantId={} msg={}", tenantId, e.getMessage());
            }
        }
        return localUploadedSearch(tenantId, query, topK);
    }

    /**
     * 本地降级召回：内置语料按 bigram 相似度排序
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

    /**
     * 本地降级召回：租户分段按 bigram 相似度排序
     * <p>
     * 只取候选上限内的最近分段，避免知识量大时一次性把全部分段载入内存。
     */
    private List<RagChunkVO> localUploadedSearch(String tenantId, String query, int topK) {
        int limit = Math.max(1, properties.getKnowledge().getMaxLocalCandidates());
        List<AssistantKnowledgeChunk> candidates = chunkMapper.selectCandidates(tenantId, limit);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<RagChunkVO> scored = new ArrayList<>(candidates.size());
        for (AssistantKnowledgeChunk chunk : candidates) {
            String source = StrUtil.blankToDefault(chunk.getSource(), "知识库文档");
            double score = SimilarityUtil.textSimilarity(query, source + chunk.getContent());
            scored.add(new RagChunkVO(chunk.getContent(), source, score));
        }
        scored.sort(Comparator.comparingDouble(RagChunkVO::getScore).reversed());
        return scored.size() > topK ? new ArrayList<>(scored.subList(0, topK)) : scored;
    }

    private List<RagChunkVO> topK(List<RagChunkVO> chunks, int k) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return chunks.stream()
                .sorted(Comparator.comparingDouble(RagChunkVO::getScore).reversed())
                .limit(k)
                .toList();
    }

    /**
     * 懒加载内置语料索引：只在首次检索时触发，失败不阻断对话
     */
    private void ensureIndexed() {
        if (indexChecked) {
            return;
        }
        synchronized (this) {
            if (indexChecked) {
                return;
            }
            // 向量库未就绪时不置位，等 Chroma 恢复后下一轮检索自动补建索引
            if (!vectorEnabled()) {
                return;
            }
            try {
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeyConstant.ASSISTANT_KB_INDEXED))) {
                    collectionRef = chromaVectorService.ensureNamedCollection(AssistantKnowledgeConstant.COLLECTION_NAME);
                    indexChecked = true;
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
                indexChecked = true;
                log.info("助手知识库索引完成 docs={} collection={}", docs.size(), ref.name());
            } catch (Exception e) {
                log.warn("助手知识库索引失败，后续走本地召回 msg={}", e.getMessage());
                collectionRef = null;
            }
        }
    }
}
