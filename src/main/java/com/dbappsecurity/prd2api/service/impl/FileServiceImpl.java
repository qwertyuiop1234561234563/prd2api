package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.config.SentinelConfig;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.dto.FileQueryDTO;
import com.dbappsecurity.prd2api.entity.FileMeta;
import com.dbappsecurity.prd2api.entity.PrdTask;
import com.dbappsecurity.prd2api.mapper.FileMetaMapper;
import com.dbappsecurity.prd2api.mapper.PrdTaskCacheMapper;
import com.dbappsecurity.prd2api.mapper.PrdTaskMapper;
import com.dbappsecurity.prd2api.service.FileService;
import com.dbappsecurity.prd2api.service.RagService;
import com.dbappsecurity.prd2api.service.TenantQuotaService;
import com.dbappsecurity.prd2api.util.DocParseUtil;
import com.dbappsecurity.prd2api.util.FileUtil;
import com.dbappsecurity.prd2api.util.RateLimitUtil;
import com.dbappsecurity.prd2api.util.SimilarityUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.FileMetaVO;
import com.dbappsecurity.prd2api.vo.FileUploadVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 文件接入实现：双重校验、md5 去重、相似度查重、多存储、解析缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private static final String STORAGE_LOCAL = "local";
    private static final double SIMILAR_THRESHOLD = 0.92D;
    private static final int SIMILAR_CANDIDATE_SIZE = 20;
    private static final long TEXT_CACHE_DAYS = 7L;

    private final FileMetaMapper fileMetaMapper;
    private final PrdTaskMapper prdTaskMapper;
    private final PrdTaskCacheMapper prdTaskCacheMapper;
    private final TenantQuotaService tenantQuotaService;
    private final RagService ragService;
    private final RateLimitUtil rateLimitUtil;
    private final Prd2ApiProperties properties;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public FileUploadVO upload(MultipartFile file) {
        String tenantId = TenantContextUtil.requireTenantId();
        String userKey = TenantContextUtil.requireUserKey();
        rateLimitUtil.check(RedisKeyConstant.limitUpload(tenantId, userKey),
                properties.getLimit().getUploadPerMinute());

        Entry entry = null;
        try {
            entry = SphU.entry(SentinelConfig.RES_UPLOAD);
            if (file == null || file.isEmpty()) {
                throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "上传文件不能为空");
            }
            if (file.getSize() > FileUtil.MAX_FILE_SIZE) {
                throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "文件超过 20MB 限制");
            }
            String fileName = StrUtil.blankToDefault(file.getOriginalFilename(), "unknown");
            String suffix = FileUtil.checkSuffix(fileName);
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException e) {
                throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "文件读取失败", e);
            }
            FileUtil.magicCheck(bytes, suffix);
            if (!FileUtil.virusScan(bytes, fileName)) {
                throw new BizException(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED, "文件未通过病毒扫描");
            }

            String md5 = FileUtil.md5(bytes);
            FileMeta exists = fileMetaMapper.selectByTenantAndMd5(tenantId, md5);
            if (exists != null) {
                log.info("文件 md5 去重命中 tenantId={} md5={}", tenantId, md5);
                return FileUploadVO.from(exists, true);
            }

            tenantQuotaService.checkUploadQuota(tenantId);

            // 解析文本并缓存，供任务解析与相似度查重复用
            String text = DocParseUtil.parse(bytes, suffix);
            if (StrUtil.isBlank(text)) {
                throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "文档解析内容为空，请检查文件是否可读");
            }
            cacheText(md5, text);
            SimilarResult similar = findSimilarDocument(tenantId, text, md5);

            String storePath = store(bytes, tenantId, suffix);
            FileMeta meta = new FileMeta();
            meta.setTenantId(tenantId);
            meta.setFileName(fileName);
            meta.setFileSuffix(suffix);
            meta.setFileStorePath(storePath);
            meta.setFileMd5(md5);
            meta.setFileSize(file.getSize());
            fileMetaMapper.insert(meta);

            FileUploadVO vo = FileUploadVO.from(meta, false);
            if (similar != null) {
                vo.setSimilarFileMetaId(similar.meta().getFileMetaId());
                vo.setSimilarity(Math.round(similar.score() * 1000) / 1000.0D);
                log.warn("检测到相似文档 tenantId={} newMd5={} similarId={} similarity={}",
                        tenantId, md5, similar.meta().getFileMetaId(), similar.score());
            }
            return vo;
        } catch (BlockException e) {
            throw new BizException(BizErrorCodeEnum.RATE_LIMITED, "上传请求过多，请稍后重试");
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    @Override
    public Page<FileMetaVO> pageFiles(FileQueryDTO dto) {
        LambdaQueryWrapper<FileMeta> wrapper = new LambdaQueryWrapper<>();
        if (!TenantContextUtil.isAdmin()) {
            wrapper.eq(FileMeta::getTenantId, TenantContextUtil.requireTenantId());
        } else if (StrUtil.isNotBlank(dto.getTenantId())) {
            wrapper.eq(FileMeta::getTenantId, dto.getTenantId());
        }
        wrapper.like(StrUtil.isNotBlank(dto.getFileName()), FileMeta::getFileName, dto.getFileName())
                .orderByDesc(FileMeta::getCreateTime);

        Page<FileMeta> page = fileMetaMapper.selectPage(new Page<>(dto.getPageNum(), dto.getPageSize()), wrapper);
        Page<FileMetaVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(FileMetaVO::from).toList());
        return result;
    }

    @Override
    public String loadText(FileMeta fileMeta) {
        if (fileMeta == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "文件不存在");
        }
        String cacheKey = RedisKeyConstant.docText(fileMeta.getFileMd5());
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            return cached;
        }
        byte[] bytes = FileUtil.readLocal(fileMeta.getFileStorePath());
        String text = DocParseUtil.parse(bytes, fileMeta.getFileSuffix());
        if (StrUtil.isBlank(text)) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "文档解析内容为空，请检查文件是否可读");
        }
        stringRedisTemplate.opsForValue().set(cacheKey, text, TEXT_CACHE_DAYS, TimeUnit.DAYS);
        return text;
    }

    @Override
    public void deleteFile(String fileMetaId) {
        FileMeta meta = fileMetaMapper.selectById(fileMetaId);
        if (meta == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "文件不存在");
        }
        checkTenant(meta.getTenantId());
        dropFileVectors(meta.getTenantId(), meta.getFileMetaId());
        FileUtil.deleteLocalQuietly(meta.getFileStorePath());
        fileMetaMapper.deleteById(fileMetaId);
        stringRedisTemplate.delete(RedisKeyConstant.docText(meta.getFileMd5()));
        log.info("文件已删除 fileMetaId={} operatorTenant={}", fileMetaId, TenantContextUtil.getTenantId());
    }

    @Override
    public int cleanExpired() {
        int expireDay = Math.max(1, properties.getClean().getExpireDay());
        LocalDateTime expireBefore = LocalDateTime.now().minusDays(expireDay);
        List<FileMeta> expiredFiles = fileMetaMapper.selectExpired(expireBefore);
        for (FileMeta meta : expiredFiles) {
            dropFileVectors(meta.getTenantId(), meta.getFileMetaId());
            FileUtil.deleteLocalQuietly(meta.getFileStorePath());
            fileMetaMapper.deleteById(meta.getFileMetaId());
            stringRedisTemplate.delete(RedisKeyConstant.docText(meta.getFileMd5()));
        }
        if (!expiredFiles.isEmpty()) {
            log.info("清理过期文件 {} 个，阈值={} 天", expiredFiles.size(), expireDay);
        }
        return expiredFiles.size();
    }

    /**
     * 清理文件关联任务的向量集合，避免过期向量长期占用 Chroma 存储
     */
    private void dropFileVectors(String tenantId, String fileMetaId) {
        List<PrdTask> tasks = prdTaskMapper.selectList(new LambdaQueryWrapper<PrdTask>()
                .select(PrdTask::getTaskId)
                .eq(PrdTask::getTenantId, tenantId)
                .eq(PrdTask::getFileMetaId, fileMetaId));
        for (PrdTask task : tasks) {
            ragService.dropTaskIndex(task.getTaskId());
            stringRedisTemplate.delete(RedisKeyConstant.taskContext(task.getTaskId()));
        }
    }

    @Override
    public int cleanExpiredCache() {
        int deleted = prdTaskCacheMapper.deleteExpired(LocalDateTime.now());
        if (deleted > 0) {
            log.info("清理过期报告缓存 {} 条", deleted);
        }
        return deleted;
    }

    @Override
    public int cleanExpiredExports() {
        Path dir = Paths.get(properties.getExport().getDir());
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        long deadline = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        int deleted = 0;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> expired = stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis() < deadline;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
            for (Path path : expired) {
                Files.deleteIfExists(path);
                deleted++;
            }
        } catch (IOException e) {
            log.warn("清理导出临时文件失败 dir={}", dir, e);
        }
        if (deleted > 0) {
            log.info("清理过期导出文件 {} 个", deleted);
        }
        return deleted;
    }

    @Override
    public int deleteTenantFiles(String tenantId) {
        List<FileMeta> files = fileMetaMapper.selectByTenantId(tenantId);
        for (FileMeta meta : files) {
            FileUtil.deleteLocalQuietly(meta.getFileStorePath());
            stringRedisTemplate.delete(RedisKeyConstant.docText(meta.getFileMd5()));
        }
        fileMetaMapper.delete(new LambdaQueryWrapper<FileMeta>().eq(FileMeta::getTenantId, tenantId));
        log.info("清理租户文件 tenantId={} 数量={}", tenantId, files.size());
        return files.size();
    }

    private String store(byte[] bytes, String tenantId, String suffix) {
        String mode = StrUtil.blankToDefault(properties.getStorage().getMode(), STORAGE_LOCAL);
        if (STORAGE_LOCAL.equalsIgnoreCase(mode)) {
            return FileUtil.saveLocal(properties.getStorage().getLocalPath(), tenantId, bytes, suffix);
        }
        // 预留对象存储适配点：接入 OSS/S3 SDK 后在此实现上传，返回对象 key
        throw new BizException(BizErrorCodeEnum.BIZ_ERROR,
                "当前存储模式为 " + mode + "，对象存储适配器尚未接入，请设置 prd2api.storage.mode=local");
    }

    private void cacheText(String md5, String text) {
        stringRedisTemplate.opsForValue()
                .set(RedisKeyConstant.docText(md5), text, TEXT_CACHE_DAYS, TimeUnit.DAYS);
    }

    /**
     * 相似度查重：与租户最近 20 个已解析文档比较（只读缓存文本，不额外解析）
     */
    private SimilarResult findSimilarDocument(String tenantId, String text, String md5) {
        List<FileMeta> candidates = fileMetaMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getTenantId, tenantId)
                .ne(FileMeta::getFileMd5, md5)
                .orderByDesc(FileMeta::getCreateTime)
                .last("limit " + SIMILAR_CANDIDATE_SIZE));
        SimilarResult best = null;
        for (FileMeta candidate : candidates) {
            String cached = stringRedisTemplate.opsForValue()
                    .get(RedisKeyConstant.docText(candidate.getFileMd5()));
            if (StrUtil.isBlank(cached)) {
                continue;
            }
            double score = SimilarityUtil.documentSimilarity(text, cached);
            if (score >= SIMILAR_THRESHOLD && (best == null || score > best.score())) {
                best = new SimilarResult(candidate, score);
            }
        }
        return best;
    }

    private void checkTenant(String tenantId) {
        if (!TenantContextUtil.isAdmin() && !tenantId.equals(TenantContextUtil.requireTenantId())) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "无权操作其他租户文件");
        }
    }

    private record SimilarResult(FileMeta meta, double score) {
    }
}
