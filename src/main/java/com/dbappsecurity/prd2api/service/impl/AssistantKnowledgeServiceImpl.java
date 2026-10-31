package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.AsyncThreadPoolConfig;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.KnowledgeDocStatus;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.dto.KnowledgeDocQueryDTO;
import com.dbappsecurity.prd2api.entity.AssistantKnowledgeChunk;
import com.dbappsecurity.prd2api.entity.AssistantKnowledgeDoc;
import com.dbappsecurity.prd2api.mapper.AssistantKnowledgeChunkMapper;
import com.dbappsecurity.prd2api.mapper.AssistantKnowledgeDocMapper;
import com.dbappsecurity.prd2api.rag.ChromaVectorService;
import com.dbappsecurity.prd2api.rag.EmbeddingService;
import com.dbappsecurity.prd2api.rag.KnowledgeBaseService;
import com.dbappsecurity.prd2api.service.AssistantKnowledgeService;
import com.dbappsecurity.prd2api.service.QuotaResolver;
import com.dbappsecurity.prd2api.util.ChunkUtil;
import com.dbappsecurity.prd2api.util.DocParseUtil;
import com.dbappsecurity.prd2api.util.FileUtil;
import com.dbappsecurity.prd2api.util.RateLimitUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.KnowledgeChunkVO;
import com.dbappsecurity.prd2api.vo.KnowledgeDocVO;
import com.dbappsecurity.prd2api.vo.RagChunkVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 助手知识库实现：上传 → 解析 → 切分 → 向量化 → 可检索
 * <p>
 * 参考 Dify / FastGPT 的知识库流水线：文档先入库再异步建索引，状态机对用户可见；
 * 向量库不可用时保留分段正文并走本地降级召回，保证无 Chroma 环境也能用。
 */
@Slf4j
@Service
public class AssistantKnowledgeServiceImpl implements AssistantKnowledgeService {

    private static final String STORAGE_LOCAL = "local";
    /** 分段批量入库大小，避免单条 insert 在大文档下过慢 */
    private static final int CHUNK_INSERT_BATCH = 200;
    private static final int ERROR_MSG_MAX = 500;
    private static final String KNOWLEDGE_PATH = "knowledge";
    /** 逻辑删除时改写 md5 的前缀，用于释放去重唯一键 */
    private static final String DELETE_MD5_PREFIX = "del-";

    private final AssistantKnowledgeDocMapper docMapper;
    private final AssistantKnowledgeChunkMapper chunkMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingService embeddingService;
    private final ChromaVectorService chromaVectorService;
    private final RateLimitUtil rateLimitUtil;
    private final QuotaResolver quotaResolver;
    private final Prd2ApiProperties properties;
    private final ThreadPoolTaskExecutor fileExecutor;

    public AssistantKnowledgeServiceImpl(AssistantKnowledgeDocMapper docMapper,
                                         AssistantKnowledgeChunkMapper chunkMapper,
                                         KnowledgeBaseService knowledgeBaseService,
                                         EmbeddingService embeddingService,
                                         ChromaVectorService chromaVectorService,
                                         RateLimitUtil rateLimitUtil,
                                         QuotaResolver quotaResolver,
                                         Prd2ApiProperties properties,
                                         @Qualifier(AsyncThreadPoolConfig.FILE_EXECUTOR)
                                         ThreadPoolTaskExecutor fileExecutor) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.embeddingService = embeddingService;
        this.chromaVectorService = chromaVectorService;
        this.rateLimitUtil = rateLimitUtil;
        this.quotaResolver = quotaResolver;
        this.properties = properties;
        this.fileExecutor = fileExecutor;
    }

    @Override
    public KnowledgeDocVO upload(MultipartFile file, String strategy) {
        // 知识库入库会改变全局召回结果，仅管理员可操作（拦截器之外再兜底一次）
        TenantContextUtil.requireAdmin();
        String tenantId = TenantContextUtil.requireTenantId();
        String actor = actor();
        rateLimitUtil.check(RedisKeyConstant.limitKnowledge(tenantId, actor),
                properties.getKnowledge().getUploadPerMinute());
        if (file == null || file.isEmpty()) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "上传文件不能为空");
        }
        // 知识库上限同时受套餐额度约束，避免 VIP 差异被绕过
        int maxFileSizeMb = Math.min(properties.getKnowledge().getMaxDocSizeMb(),
                quotaResolver.maxFileSizeMb());
        if (file.getSize() > maxFileSizeMb * 1024L * 1024L) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID,
                    "知识文档超过 " + maxFileSizeMb + "MB 限制");
        }
        String fileName = FileUtil.sanitizeFileName(file.getOriginalFilename());
        String suffix = FileUtil.checkSuffix(fileName);
        String storageMode = StrUtil.blankToDefault(properties.getStorage().getMode(), STORAGE_LOCAL);
        if (!STORAGE_LOCAL.equalsIgnoreCase(storageMode)) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR,
                    "当前存储模式为 " + storageMode + "，知识库仅支持本地存储");
        }
        FileUtil.StoredFile stored;
        try {
            stored = FileUtil.saveLocalStreaming(
                    properties.getStorage().getLocalPath() + "/" + KNOWLEDGE_PATH,
                    tenantId, file.getInputStream(), suffix);
        } catch (IOException e) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "文件读取失败", e);
        }
        if (!FileUtil.virusScan(fileName, stored.size())) {
            FileUtil.deleteLocalQuietly(stored.path());
            throw new BizException(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED, "文件未通过病毒扫描");
        }

        // 租户内 md5 去重：内容相同直接复用已有文档，避免重复消耗 embedding
        AssistantKnowledgeDoc exists = docMapper.selectOne(new LambdaQueryWrapper<AssistantKnowledgeDoc>()
                .eq(AssistantKnowledgeDoc::getTenantId, tenantId)
                .eq(AssistantKnowledgeDoc::getFileMd5, stored.md5())
                .orderByDesc(AssistantKnowledgeDoc::getCreateTime)
                .last("limit 1"));
        if (exists != null) {
            FileUtil.deleteLocalQuietly(stored.path());
            KnowledgeDocVO vo = KnowledgeDocVO.from(exists);
            vo.setDuplicated(true);
            return vo;
        }

        long docCount = docMapper.selectCount(new LambdaQueryWrapper<AssistantKnowledgeDoc>()
                .eq(AssistantKnowledgeDoc::getTenantId, tenantId));
        if (docCount >= properties.getKnowledge().getMaxDocsPerTenant()) {
            FileUtil.deleteLocalQuietly(stored.path());
            throw new BizException(BizErrorCodeEnum.QUOTA_EXCEEDED,
                    "知识库文档数已达上限 " + properties.getKnowledge().getMaxDocsPerTenant()
                            + "，请先删除旧文档");
        }

        LocalDateTime now = LocalDateTime.now();
        AssistantKnowledgeDoc doc = new AssistantKnowledgeDoc();
        doc.setDocId(IdUtil.fastSimpleUUID());
        doc.setTenantId(tenantId);
        doc.setDocName(fileName);
        doc.setFileSuffix(suffix);
        doc.setFileStorePath(stored.path());
        doc.setFileMd5(stored.md5());
        doc.setFileSize(stored.size());
        doc.setChunkStrategy(StrUtil.blankToDefault(strategy, properties.getChunk().getStrategy()));
        doc.setCharCount(0);
        doc.setChunkCount(0);
        doc.setStatus(KnowledgeDocStatus.PENDING.name());
        doc.setEnabled(1);
        doc.setVectorIndexed(0);
        doc.setCreateBy(actor);
        doc.setCreateTime(now);
        doc.setUpdateTime(now);
        doc.setDeleted(0);
        try {
            docMapper.insert(doc);
        } catch (DuplicateKeyException e) {
            // 并发上传同内容：后到者复用先入库记录并清理本次副本
            FileUtil.deleteLocalQuietly(stored.path());
            AssistantKnowledgeDoc concurrent = docMapper.selectOne(
                    new LambdaQueryWrapper<AssistantKnowledgeDoc>()
                            .eq(AssistantKnowledgeDoc::getTenantId, tenantId)
                            .eq(AssistantKnowledgeDoc::getFileMd5, stored.md5())
                            .last("limit 1"));
            if (concurrent == null) {
                throw e;
            }
            KnowledgeDocVO vo = KnowledgeDocVO.from(concurrent);
            vo.setDuplicated(true);
            return vo;
        }

        submit(doc.getDocId(), tenantId, actor);
        log.info("知识文档已入库，等待索引 docId={} tenantId={} name={}",
                doc.getDocId(), tenantId, fileName);
        return KnowledgeDocVO.from(doc);
    }

    @Override
    public Page<KnowledgeDocVO> page(KnowledgeDocQueryDTO dto) {
        // 知识库属于管理视图：仅管理员可见，可按 tenantId 切换查看其他租户
        TenantContextUtil.requireAdmin();
        String tenantId = StrUtil.blankToDefault(dto.getTenantId(),
                TenantContextUtil.requireTenantId());
        LambdaQueryWrapper<AssistantKnowledgeDoc> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssistantKnowledgeDoc::getTenantId, tenantId);
        String status = validStatus(dto.getStatus());
        if (StrUtil.isNotBlank(dto.getStatus()) && status == null) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID,
                    "status 仅支持 PENDING/PARSING/CHUNKING/EMBEDDING/COMPLETED/FAILED");
        }
        wrapper.like(StrUtil.isNotBlank(dto.getKeyword()),
                        AssistantKnowledgeDoc::getDocName, dto.getKeyword())
                .eq(status != null, AssistantKnowledgeDoc::getStatus, status)
                .orderByDesc(AssistantKnowledgeDoc::getCreateTime);
        Page<AssistantKnowledgeDoc> page = docMapper.selectPage(
                new Page<>(dto.getPageNum(), dto.getPageSize()), wrapper);
        Page<KnowledgeDocVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(KnowledgeDocVO::from).toList());
        return result;
    }

    @Override
    public Page<KnowledgeChunkVO> pageChunks(String docId, int pageNum, int pageSize) {
        AssistantKnowledgeDoc doc = requireDoc(docId);
        Page<AssistantKnowledgeChunk> page = chunkMapper.selectPage(
                new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100)),
                new LambdaQueryWrapper<AssistantKnowledgeChunk>()
                        .eq(AssistantKnowledgeChunk::getDocId, doc.getDocId())
                        .orderByAsc(AssistantKnowledgeChunk::getChunkNo));
        Page<KnowledgeChunkVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(KnowledgeChunkVO::from).toList());
        return result;
    }

    @Override
    public void setEnabled(String docId, boolean enabled) {
        AssistantKnowledgeDoc doc = requireDoc(docId);
        // 索引构建中切换启停会与异步任务争抢向量写入，这里直接拒绝，等状态稳定后再操作
        if (KnowledgeDocStatus.running(doc.getStatus())) {
            throw new BizException(BizErrorCodeEnum.TASK_STATE_ERROR, "文档正在建索引，请稍后再试");
        }
        if (!enabled) {
            deleteVectorsBestEffort(doc);
            docMapper.update(null, new LambdaUpdateWrapper<AssistantKnowledgeDoc>()
                    .eq(AssistantKnowledgeDoc::getDocId, doc.getDocId())
                    .set(AssistantKnowledgeDoc::getEnabled, 0)
                    .set(AssistantKnowledgeDoc::getVectorIndexed, 0)
                    .set(AssistantKnowledgeDoc::getUpdateTime, LocalDateTime.now()));
            knowledgeBaseService.evictTenantCollection(doc.getTenantId());
            return;
        }
        docMapper.update(null, new LambdaUpdateWrapper<AssistantKnowledgeDoc>()
                .eq(AssistantKnowledgeDoc::getDocId, doc.getDocId())
                .set(AssistantKnowledgeDoc::getEnabled, 1)
                .set(AssistantKnowledgeDoc::getUpdateTime, LocalDateTime.now()));
        // 索引未完成或向量缺失（例如上次向量库不可用）时顺带重建，保证启用即可检索
        boolean needReindex = !KnowledgeDocStatus.COMPLETED.name().equals(doc.getStatus())
                || (knowledgeBaseService.vectorEnabled() && doc.getVectorIndexed() != null
                && doc.getVectorIndexed() == 0);
        if (needReindex) {
            submit(doc.getDocId(), doc.getTenantId(), actor());
        }
    }

    @Override
    public void rebuild(String docId) {
        AssistantKnowledgeDoc doc = requireDoc(docId);
        if (KnowledgeDocStatus.running(doc.getStatus())) {
            throw new BizException(BizErrorCodeEnum.TASK_STATE_ERROR, "文档正在建索引，请稍后再试");
        }
        updateStatus(doc.getDocId(), KnowledgeDocStatus.PENDING, null);
        submit(doc.getDocId(), doc.getTenantId(), actor());
    }

    @Override
    public void delete(String docId) {
        AssistantKnowledgeDoc doc = requireDoc(docId);
        // 异步流水线仍在写分段/向量，删除会留下孤儿数据，等任务结束再删
        if (KnowledgeDocStatus.running(doc.getStatus())) {
            throw new BizException(BizErrorCodeEnum.TASK_STATE_ERROR, "文档正在建索引，请稍后再试");
        }
        List<AssistantKnowledgeChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<AssistantKnowledgeChunk>()
                        .eq(AssistantKnowledgeChunk::getDocId, doc.getDocId())
                        .select(AssistantKnowledgeChunk::getId));
        if (doc.getVectorIndexed() != null && doc.getVectorIndexed() == 1) {
            deleteVectorsBestEffort(doc, chunks);
        }
        chunkMapper.delete(new LambdaQueryWrapper<AssistantKnowledgeChunk>()
                .eq(AssistantKnowledgeChunk::getDocId, doc.getDocId()));
        // 唯一键是 (tenant_id, file_md5, deleted)，逻辑删除时若原样置 deleted=1 会和历史已删记录冲突；
        // 这里释放原 md5（改写为 del-{docId}），既保证删除成功，也让同内容可以重新上传
        docMapper.update(null, new LambdaUpdateWrapper<AssistantKnowledgeDoc>()
                .eq(AssistantKnowledgeDoc::getDocId, doc.getDocId())
                .eq(AssistantKnowledgeDoc::getDeleted, 0)
                .set(AssistantKnowledgeDoc::getDeleted, 1)
                .set(AssistantKnowledgeDoc::getFileMd5, DELETE_MD5_PREFIX + doc.getDocId())
                .set(AssistantKnowledgeDoc::getUpdateTime, LocalDateTime.now()));
        FileUtil.deleteLocalQuietly(doc.getFileStorePath());
        knowledgeBaseService.evictTenantCollection(doc.getTenantId());
        log.info("知识文档已删除 docId={} tenantId={} vectors={}",
                doc.getDocId(), doc.getTenantId(), chunks.size());
    }

    @Override
    public Map<String, Object> stats() {
        String tenantId = TenantContextUtil.requireTenantId();
        Map<String, Object> stats = new LinkedHashMap<>();
        for (KnowledgeDocStatus status : KnowledgeDocStatus.values()) {
            stats.put(status.name(), docMapper.selectCount(
                    new LambdaQueryWrapper<AssistantKnowledgeDoc>()
                            .eq(AssistantKnowledgeDoc::getTenantId, tenantId)
                            .eq(AssistantKnowledgeDoc::getStatus, status.name())));
        }
        stats.put("totalDocs", docMapper.selectCount(
                new LambdaQueryWrapper<AssistantKnowledgeDoc>()
                        .eq(AssistantKnowledgeDoc::getTenantId, tenantId)));
        stats.put("totalChunks", chunkMapper.selectCount(
                new LambdaQueryWrapper<AssistantKnowledgeChunk>()
                        .eq(AssistantKnowledgeChunk::getTenantId, tenantId)));
        stats.put("vectorEnabled", knowledgeBaseService.vectorEnabled());
        return stats;
    }

    @Override
    public List<RagChunkVO> search(String query, int topK) {
        if (StrUtil.isBlank(query)) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "query 不能为空");
        }
        return knowledgeBaseService.search(query, topK);
    }

    /**
     * 异步索引任务：切分线程池隔离，避免阻塞上传请求
     */
    private void submit(String docId, String tenantId, String actor) {
        String userId = TenantContextUtil.getUserId();
        String role = TenantContextUtil.getRole();
        try {
            fileExecutor.execute(() -> {
                // 请求线程的租户上下文不会带到线程池，先捕获再恢复
                TenantContextUtil.set(userId, tenantId, actor, role);
                try {
                    process(docId);
                } finally {
                    TenantContextUtil.clear();
                }
            });
        } catch (RuntimeException e) {
            // 队列满等提交失败场景：标记 FAILED，避免文档永久停在 PENDING 无法重建/删除
            updateStatus(docId, KnowledgeDocStatus.FAILED, "索引任务队列繁忙，请稍后重建");
            log.warn("知识文档索引任务提交失败 docId={} msg={}", docId, e.getMessage());
        }
    }

    /**
     * 索引流水线：解析 → 切分 → 入库 → 向量化 → 完成
     */
    private void process(String docId) {
        AssistantKnowledgeDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            // 文档在排队期间被删除，直接结束
            return;
        }
        try {
            updateStatus(docId, KnowledgeDocStatus.PARSING, null);
            String text = DocParseUtil.parse(Paths.get(doc.getFileStorePath()), doc.getFileSuffix());
            if (StrUtil.isBlank(text)) {
                throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "文档解析内容为空，请检查文件是否可读");
            }

            updateStatus(docId, KnowledgeDocStatus.CHUNKING, null);
            int fixedSize = properties.getChunk().getFixedSize();
            int overlap = properties.getChunk().getOverlap();
            List<String> pieces = ChunkUtil.chunk(text, doc.getChunkStrategy(), fixedSize, overlap);
            if (pieces.isEmpty()) {
                throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "文档未产生有效分段");
            }
            int maxChunks = properties.getKnowledge().getMaxChunksPerDoc();
            if (pieces.size() > maxChunks) {
                throw new BizException(BizErrorCodeEnum.BIZ_ERROR,
                        "文档分段数 " + pieces.size() + " 超过上限 " + maxChunks + "，请拆分后上传");
            }

            // 重建：先移除旧分段与旧向量，避免同一文档出现重复片段
            purgeOldChunks(doc);
            List<AssistantKnowledgeChunk> chunks = buildChunks(doc, pieces);
            for (int from = 0; from < chunks.size(); from += CHUNK_INSERT_BATCH) {
                int to = Math.min(chunks.size(), from + CHUNK_INSERT_BATCH);
                chunkMapper.insertBatch(chunks.subList(from, to));
            }

            updateStatus(docId, KnowledgeDocStatus.EMBEDDING, null);
            boolean vectorized = false;
            if (knowledgeBaseService.vectorEnabled()) {
                vectorized = writeVectors(doc, chunks);
            } else {
                log.info("向量库不可用，知识文档仅保留分段供本地召回 docId={}", docId);
            }

            docMapper.update(null, new LambdaUpdateWrapper<AssistantKnowledgeDoc>()
                    .eq(AssistantKnowledgeDoc::getDocId, docId)
                    .set(AssistantKnowledgeDoc::getStatus, KnowledgeDocStatus.COMPLETED.name())
                    .set(AssistantKnowledgeDoc::getCharCount, text.length())
                    .set(AssistantKnowledgeDoc::getChunkCount, chunks.size())
                    .set(AssistantKnowledgeDoc::getVectorIndexed, vectorized ? 1 : 0)
                    .set(AssistantKnowledgeDoc::getErrorMsg, null)
                    .set(AssistantKnowledgeDoc::getUpdateTime, LocalDateTime.now()));
            knowledgeBaseService.evictTenantCollection(doc.getTenantId());
            log.info("知识文档索引完成 docId={} chunks={} vectorized={}",
                    docId, chunks.size(), vectorized);
        } catch (Exception e) {
            String reason = StrUtil.maxLength(StrUtil.blankToDefault(e.getMessage(), "索引失败"), ERROR_MSG_MAX);
            updateStatus(docId, KnowledgeDocStatus.FAILED, reason);
            log.warn("知识文档索引失败 docId={} msg={}", docId, reason, e);
        }
    }

    private List<AssistantKnowledgeChunk> buildChunks(AssistantKnowledgeDoc doc, List<String> pieces) {
        LocalDateTime now = LocalDateTime.now();
        List<AssistantKnowledgeChunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            AssistantKnowledgeChunk chunk = new AssistantKnowledgeChunk();
            chunk.setDocId(doc.getDocId());
            chunk.setTenantId(doc.getTenantId());
            chunk.setChunkNo(i + 1);
            chunk.setContent(pieces.get(i));
            chunk.setCharCount(pieces.get(i).length());
            chunk.setSource(StrUtil.maxLength("《" + doc.getDocName() + "》#" + (i + 1), 160));
            chunk.setCreateTime(now);
            chunk.setDeleted(0);
            chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * 批量向量化并写入租户 collection
     *
     * @return 是否全部写入成功
     */
    private boolean writeVectors(AssistantKnowledgeDoc doc, List<AssistantKnowledgeChunk> chunks) {
        try {
            ChromaVectorService.CollectionRef collection =
                    knowledgeBaseService.ensureTenantCollection(doc.getTenantId());
            int batchSize = Math.max(1, properties.getKnowledge().getEmbedBatchSize());
            for (int from = 0; from < chunks.size(); from += batchSize) {
                int to = Math.min(chunks.size(), from + batchSize);
                List<AssistantKnowledgeChunk> batch = chunks.subList(from, to);
                List<String> ids = new ArrayList<>(batch.size());
                List<String> contents = new ArrayList<>(batch.size());
                List<String> sources = new ArrayList<>(batch.size());
                List<float[]> embeddings = new ArrayList<>(batch.size());
                for (AssistantKnowledgeChunk chunk : batch) {
                    ids.add(String.valueOf(chunk.getId()));
                    contents.add(chunk.getContent());
                    sources.add(chunk.getSource());
                    embeddings.add(embeddingService.embed(chunk.getContent()));
                }
                chromaVectorService.addChunks(collection.id(), ids, embeddings, contents, sources);
            }
            return true;
        } catch (Exception e) {
            // 向量写入失败不阻断完成：分段正文仍在库中，可走本地召回
            log.warn("知识文档向量写入失败，降级本地召回 docId={} msg={}", doc.getDocId(), e.getMessage());
            return false;
        }
    }

    /**
     * 重建前清理旧分段：先尝试精确删除向量，再逻辑删除分段
     */
    private void purgeOldChunks(AssistantKnowledgeDoc doc) {
        List<AssistantKnowledgeChunk> old = chunkMapper.selectList(
                new LambdaQueryWrapper<AssistantKnowledgeChunk>()
                        .eq(AssistantKnowledgeChunk::getDocId, doc.getDocId())
                        .select(AssistantKnowledgeChunk::getId));
        if (doc.getVectorIndexed() != null && doc.getVectorIndexed() == 1) {
            deleteVectorsBestEffort(doc, old);
        }
        chunkMapper.delete(new LambdaQueryWrapper<AssistantKnowledgeChunk>()
                .eq(AssistantKnowledgeChunk::getDocId, doc.getDocId()));
    }

    private void deleteVectorsBestEffort(AssistantKnowledgeDoc doc) {
        List<AssistantKnowledgeChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<AssistantKnowledgeChunk>()
                        .eq(AssistantKnowledgeChunk::getDocId, doc.getDocId())
                        .select(AssistantKnowledgeChunk::getId));
        deleteVectorsBestEffort(doc, chunks);
    }

    private void deleteVectorsBestEffort(AssistantKnowledgeDoc doc, List<AssistantKnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            ChromaVectorService.CollectionRef collection =
                    knowledgeBaseService.ensureTenantCollection(doc.getTenantId());
            List<String> ids = chunks.stream().map(chunk -> String.valueOf(chunk.getId())).toList();
            chromaVectorService.deleteByIds(collection.id(), ids);
        } catch (Exception e) {
            // 删除向量失败不阻断业务，重建或后续清理可兜底
            log.warn("知识文档向量清理失败 docId={} msg={}", doc.getDocId(), e.getMessage());
        }
    }

    private void updateStatus(String docId, KnowledgeDocStatus status, String errorMsg) {
        docMapper.update(null, new LambdaUpdateWrapper<AssistantKnowledgeDoc>()
                .eq(AssistantKnowledgeDoc::getDocId, docId)
                .set(AssistantKnowledgeDoc::getStatus, status.name())
                .set(AssistantKnowledgeDoc::getErrorMsg, errorMsg)
                .set(AssistantKnowledgeDoc::getUpdateTime, LocalDateTime.now()));
    }

    /**
     * 文档必须属于当前租户；知识库管理整体仅管理员可用，管理员可跨租户查看
     */
    private AssistantKnowledgeDoc requireDoc(String docId) {
        TenantContextUtil.requireAdmin();
        if (StrUtil.isBlank(docId)) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "docId 不能为空");
        }
        AssistantKnowledgeDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "知识文档不存在或已删除");
        }
        return doc;
    }

    private String actor() {
        String userId = TenantContextUtil.getUserId();
        return StrUtil.blankToDefault(userId, TenantContextUtil.requireUserKey());
    }

    private String validStatus(String status) {
        if (StrUtil.isBlank(status)) {
            return null;
        }
        for (KnowledgeDocStatus item : KnowledgeDocStatus.values()) {
            if (item.name().equalsIgnoreCase(status)) {
                return item.name();
            }
        }
        return null;
    }
}
