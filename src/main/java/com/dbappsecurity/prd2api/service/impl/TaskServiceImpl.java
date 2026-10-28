package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.config.SentinelConfig;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.LlmModelEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.constant.TaskStatusEnum;
import com.dbappsecurity.prd2api.dto.AgentChatDTO;
import com.dbappsecurity.prd2api.dto.RetryTaskDTO;
import com.dbappsecurity.prd2api.dto.TaskQueryDTO;
import com.dbappsecurity.prd2api.dto.UploadFileDTO;
import com.dbappsecurity.prd2api.entity.FileMeta;
import com.dbappsecurity.prd2api.entity.PrdTask;
import com.dbappsecurity.prd2api.entity.PrdTaskCache;
import com.dbappsecurity.prd2api.mapper.FileMetaMapper;
import com.dbappsecurity.prd2api.mapper.PrdTaskCacheMapper;
import com.dbappsecurity.prd2api.mapper.PrdTaskMapper;
import com.dbappsecurity.prd2api.service.AgentService;
import com.dbappsecurity.prd2api.service.FileService;
import com.dbappsecurity.prd2api.service.RagService;
import com.dbappsecurity.prd2api.service.TaskService;
import com.dbappsecurity.prd2api.service.QuotaResolver;
import com.dbappsecurity.prd2api.util.DiffUtil;
import com.dbappsecurity.prd2api.util.ExportUtil;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.ReportParseUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.util.ChunkUtil;
import com.dbappsecurity.prd2api.util.RedisLockUtil;
import com.dbappsecurity.prd2api.vo.ReportExportVO;
import com.dbappsecurity.prd2api.vo.ReviewIssueVO;
import com.dbappsecurity.prd2api.vo.TaskDiffVO;
import com.dbappsecurity.prd2api.vo.TaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 任务服务实现：缓存命中、版本快照、异步调度、导出凭证、租户数据清理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String CONTENT_TYPE_MD = "text/markdown;charset=UTF-8";
    private static final String CONTENT_TYPE_HTML = "text/html;charset=UTF-8";
    private static final String CONTENT_TYPE_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final PrdTaskMapper prdTaskMapper;
    private final PrdTaskCacheMapper prdTaskCacheMapper;
    private final FileMetaMapper fileMetaMapper;
    private final FileService fileService;
    private final AgentService agentService;
    private final QuotaResolver quotaResolver;
    private final RagService ragService;
    private final Prd2ApiProperties properties;
    private final StringRedisTemplate stringRedisTemplate;
    private final MetricUtil metricUtil;
    private final RedisLockUtil redisLockUtil;

    @Override
    public TaskVO createTask(UploadFileDTO dto) {
        String tenantId = TenantContextUtil.requireTenantId();
        String userKey = TenantContextUtil.requireUserKey();
        Entry entry = null;
        boolean locked = false;
        String lockKey = null;
        try {
            entry = SphU.entry(SentinelConfig.RES_TASK_CREATE);
            FileMeta meta = fileMetaMapper.selectById(dto.getFileMetaId());
            if (meta == null || !tenantId.equals(meta.getTenantId())) {
                throw new BizException(BizErrorCodeEnum.NOT_FOUND, "文件不存在或不属于当前租户");
            }
            String modelCode = StrUtil.blankToDefault(dto.getModelCode(), LlmModelEnum.DEFAULT.getCode());
            LlmModelEnum.resolve(modelCode);
            String strategy = resolveStrategy(dto.getChunkStrategy());

            PrdTaskCache cache = prdTaskCacheMapper.selectValidCache(
                    tenantId, meta.getFileMd5(), modelCode, strategy, LocalDateTime.now());

            // 缓存未命中才占用并发配额，命中缓存直接返回历史结果
            if (cache == null) {
                // 并发配额校验与任务落库必须串行，避免并发请求同时通过校验造成超限
                lockKey = RedisKeyConstant.taskCreateLock(tenantId);
                if (!redisLockUtil.tryLock(lockKey, 5, 10)) {
                    throw new BizException(BizErrorCodeEnum.RATE_LIMITED, "任务创建请求过多，请稍后重试");
                }
                locked = true;
                quotaResolver.checkConcurrentQuota();
            }

            PrdTask task = new PrdTask();
            task.setTenantId(tenantId);
            task.setUserKey(userKey);
            task.setFileMetaId(meta.getFileMetaId());
            task.setModelCode(modelCode);
            if (cache != null) {
                task.setTaskStatus(TaskStatusEnum.SUCCESS.getCode());
                task.setProgress(100);
                task.setResultMarkdown(cache.getCacheResultMarkdown());
                task.setFinishTime(LocalDateTime.now());
            } else {
                task.setTaskStatus(TaskStatusEnum.INIT.getCode());
                task.setProgress(0);
            }
            insertWithVersion(task, tenantId, meta.getFileMetaId());
            metricUtil.taskCreated();

            if (cache != null) {
                log.info("报告缓存命中，直接返回历史结果 taskId={} version={}",
                        task.getTaskId(), task.getVersionNo());
                return TaskVO.from(prdTaskMapper.selectById(task.getTaskId()));
            }

            // 记录分块策略，供异步线程执行时读取
            stringRedisTemplate.opsForValue().set(RedisKeyConstant.taskContext(task.getTaskId()),
                    strategy, 1, TimeUnit.DAYS);
            agentService.executeAsync(task.getTaskId());
            log.info("任务已提交异步解析 taskId={} tenantId={} fileMetaId={} version={}",
                    task.getTaskId(), tenantId, meta.getFileMetaId(), task.getVersionNo());
            return TaskVO.from(prdTaskMapper.selectById(task.getTaskId()));
        } catch (BlockException e) {
            throw new BizException(BizErrorCodeEnum.RATE_LIMITED, "任务创建请求过多，请稍后重试");
        } finally {
            if (locked) {
                redisLockUtil.unlock(lockKey);
            }
            if (entry != null) {
                entry.exit();
            }
        }
    }

    @Override
    public TaskVO getTask(String taskId) {
        PrdTask task = prdTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "任务不存在");
        }
        checkTenant(task.getTenantId());
        return TaskVO.from(task);
    }

    @Override
    public void checkTaskAccess(String taskId) {
        PrdTask task = prdTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "任务不存在");
        }
        checkTenant(task.getTenantId());
    }

    @Override
    public Page<TaskVO> pageTasks(TaskQueryDTO dto) {
        LambdaQueryWrapper<PrdTask> wrapper = new LambdaQueryWrapper<>();
        if (TenantContextUtil.isAdmin()) {
            // 管理员不传 tenantId 时查看全租户任务
            if (StrUtil.isNotBlank(dto.getTenantId())) {
                wrapper.eq(PrdTask::getTenantId, dto.getTenantId());
            }
        } else {
            wrapper.eq(PrdTask::getTenantId, TenantContextUtil.requireTenantId());
        }
        if (StrUtil.isNotBlank(dto.getTaskStatus())) {
            if (!TaskStatusEnum.isValid(dto.getTaskStatus())) {
                throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "非法的任务状态: " + dto.getTaskStatus());
            }
            wrapper.eq(PrdTask::getTaskStatus, dto.getTaskStatus());
        }
        if (StrUtil.isNotBlank(dto.getFileMetaId())) {
            wrapper.eq(PrdTask::getFileMetaId, dto.getFileMetaId());
        }
        wrapper.orderByDesc(PrdTask::getCreateTime);

        Page<PrdTask> page = prdTaskMapper.selectPage(new Page<>(dto.getPageNum(), dto.getPageSize()), wrapper);
        Page<TaskVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(TaskVO::summary).toList());
        return result;
    }

    @Override
    public void retryTask(RetryTaskDTO dto) {
        PrdTask task = prdTaskMapper.selectById(dto.getTaskId());
        if (task == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "任务不存在");
        }
        checkTenant(task.getTenantId());
        if (!TaskStatusEnum.FAIL.getCode().equals(task.getTaskStatus())
                && !TaskStatusEnum.INIT.getCode().equals(task.getTaskStatus())
                && !TaskStatusEnum.CANCELED.getCode().equals(task.getTaskStatus())) {
            throw new BizException(BizErrorCodeEnum.TASK_STATE_ERROR, "仅失败、取消或初始状态的任务支持重试");
        }
        String modelCode = StrUtil.blankToDefault(dto.getModelCode(), task.getModelCode());
        LlmModelEnum.resolve(modelCode);
        // 重试会重新占用并发额度，先校验再改状态，避免超额任务被排入线程池
        quotaResolver.checkConcurrentQuota();

        // 重试前清除取消标记，避免重试任务立刻被中断
        stringRedisTemplate.delete(RedisKeyConstant.taskCancel(task.getTaskId()));

        // 条件更新：并发重试只有一次能成功，防止同一任务被重复提交执行
        int rows = prdTaskMapper.update(null, new LambdaUpdateWrapper<PrdTask>()
                .eq(PrdTask::getTaskId, task.getTaskId())
                .in(PrdTask::getTaskStatus, TaskStatusEnum.FAIL.getCode(),
                        TaskStatusEnum.INIT.getCode(), TaskStatusEnum.CANCELED.getCode())
                .set(PrdTask::getTaskStatus, TaskStatusEnum.INIT.getCode())
                .set(PrdTask::getProgress, 0)
                .set(PrdTask::getErrorMsg, null)
                .set(PrdTask::getFinishTime, null)
                .set(PrdTask::getModelCode, modelCode));
        if (rows == 0) {
            throw new BizException(BizErrorCodeEnum.TASK_STATE_ERROR, "任务状态已变化，请刷新后重试");
        }
        agentService.executeAsync(task.getTaskId());
        log.info("任务已重新提交 taskId={} modelCode={}", task.getTaskId(), modelCode);
    }

    @Override
    public void cancelTask(String taskId) {
        PrdTask task = loadAccessibleTask(taskId);
        String status = task.getTaskStatus();
        if (TaskStatusEnum.SUCCESS.getCode().equals(status)
                || TaskStatusEnum.FAIL.getCode().equals(status)
                || TaskStatusEnum.CANCELED.getCode().equals(status)) {
            throw new BizException(BizErrorCodeEnum.TASK_STATE_ERROR, "任务已结束，无需取消");
        }
        // 取消标记保留 1 天，流水线在阶段边界读取
        stringRedisTemplate.opsForValue().set(RedisKeyConstant.taskCancel(taskId), "1", 1, TimeUnit.DAYS);
        if (TaskStatusEnum.INIT.getCode().equals(status)) {
            // 尚未被调度器取出，直接置为取消态，执行线程启动后会再次识别并跳过
            prdTaskMapper.update(null, new LambdaUpdateWrapper<PrdTask>()
                    .eq(PrdTask::getTaskId, taskId)
                    .set(PrdTask::getTaskStatus, TaskStatusEnum.CANCELED.getCode())
                    .set(PrdTask::getErrorMsg, "任务已被用户取消")
                    .set(PrdTask::getFinishTime, LocalDateTime.now()));
        }
        metricUtil.taskCanceled();
        log.info("任务取消标记已写入 taskId={} currentStatus={}", taskId, status);
    }

    @Override
    public TaskDiffVO diffReport(String baseTaskId, String compareTaskId) {
        if (StrUtil.isBlank(compareTaskId) || baseTaskId.equals(compareTaskId)) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "请选择两个不同的任务版本进行对比");
        }
        PrdTask base = loadAccessibleTask(baseTaskId);
        PrdTask compare = loadAccessibleTask(compareTaskId);
        if (!base.getFileMetaId().equals(compare.getFileMetaId())) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "仅支持同一文档的不同版本之间对比");
        }
        checkExportable(base);
        checkExportable(compare);

        DiffUtil.DiffResult result = DiffUtil.diff(base.getResultMarkdown(), compare.getResultMarkdown());
        TaskDiffVO vo = new TaskDiffVO();
        vo.setBaseTaskId(base.getTaskId());
        vo.setBaseVersion(base.getVersionNo());
        vo.setCompareTaskId(compare.getTaskId());
        vo.setCompareVersion(compare.getVersionNo());
        vo.setAddedLines(result.added());
        vo.setRemovedLines(result.removed());
        vo.setUnchangedLines(result.unchanged());
        vo.setTruncated(result.truncated());
        vo.setDiffText(result.diffText());
        return vo;
    }

    @Override
    public List<ReviewIssueVO> extractIssues(String taskId) {
        PrdTask task = loadAccessibleTask(taskId);
        checkExportable(task);
        return ReportParseUtil.parseIssues(task.getResultMarkdown());
    }

    @Override
    public String chat(AgentChatDTO dto) {
        return agentService.chat(dto.getTaskId(), dto.getQuestion());
    }

    @Override
    public ReportExportVO exportReport(String taskId, String format) {
        PrdTask task = prdTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "任务不存在");
        }
        checkTenant(task.getTenantId());
        if (!TaskStatusEnum.SUCCESS.getCode().equals(task.getTaskStatus())
                || StrUtil.isBlank(task.getResultMarkdown())) {
            throw new BizException(BizErrorCodeEnum.TASK_STATE_ERROR, "仅解析成功的任务支持导出");
        }
        String normalized = normalizeFormat(format);
        String token = IdUtil.fastSimpleUUID();
        String fileName = "review-" + taskId + "-" + LocalDateTime.now().format(FILE_TIME) + "." + normalized;
        Path path = ExportUtil.exportPath(properties.getExport().getDir(), token + "." + normalized);

        String contentType;
        try {
            switch (normalized) {
                case "html" -> {
                    Files.writeString(path, ExportUtil.markdownToHtml(task.getResultMarkdown(), "需求评审报告"));
                    contentType = CONTENT_TYPE_HTML;
                }
                case "docx" -> {
                    ExportUtil.markdownToDocx(task.getResultMarkdown(), path);
                    contentType = CONTENT_TYPE_DOCX;
                }
                default -> {
                    Files.writeString(path, task.getResultMarkdown());
                    contentType = CONTENT_TYPE_MD;
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "报告导出失败: " + e.getMessage(), e);
        }

        int expireSeconds = Math.max(60, properties.getExport().getTokenExpireSeconds());
        String tokenValue = String.join("|", task.getTaskId(), task.getTenantId(), fileName,
                contentType, path.toAbsolutePath().toString());
        stringRedisTemplate.opsForValue().set(RedisKeyConstant.exportToken(token), tokenValue,
                expireSeconds, TimeUnit.SECONDS);

        ReportExportVO vo = new ReportExportVO();
        vo.setTaskId(taskId);
        vo.setFormat(normalized);
        vo.setFileName(fileName);
        vo.setExportToken(token);
        vo.setDownloadUrl("/api/task/export/download?token=" + token);
        vo.setExpireSeconds(expireSeconds);
        log.info("报告导出就绪 taskId={} format={} expire={}s", taskId, normalized, expireSeconds);
        return vo;
    }

    @Override
    public ExportFile loadExportFile(String token) {
        // 限定凭证格式，避免任意字符串被用作 Redis key
        if (StrUtil.isBlank(token) || !token.matches("^[a-fA-F0-9]{32}$")) {
            throw new BizException(BizErrorCodeEnum.EXPORT_TOKEN_INVALID);
        }
        // 一次性凭证：读取即删除，避免下载链接被重复使用或转发滥用
        String tokenValue = stringRedisTemplate.opsForValue().getAndDelete(RedisKeyConstant.exportToken(token));
        if (StrUtil.isBlank(tokenValue)) {
            throw new BizException(BizErrorCodeEnum.EXPORT_TOKEN_INVALID);
        }
        String[] parts = tokenValue.split("\\|", 5);
        if (parts.length < 5) {
            throw new BizException(BizErrorCodeEnum.EXPORT_TOKEN_INVALID, "导出凭证内容非法");
        }
        Resource resource = new FileSystemResource(parts[4]);
        if (!resource.exists()) {
            throw new BizException(BizErrorCodeEnum.EXPORT_TOKEN_INVALID, "导出文件已清理，请重新导出");
        }
        return new ExportFile(resource, parts[2], parts[3]);
    }

    @Override
    public void deleteTask(String taskId) {
        PrdTask task = prdTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "任务不存在");
        }
        checkTenant(task.getTenantId());
        if (TaskStatusEnum.PROCESS.getCode().equals(task.getTaskStatus())) {
            throw new BizException(BizErrorCodeEnum.TASK_STATE_ERROR, "任务执行中，暂不支持删除");
        }
        prdTaskMapper.deleteById(taskId);
        ragService.dropTaskIndex(taskId);
        stringRedisTemplate.delete(RedisKeyConstant.taskContext(taskId));
        stringRedisTemplate.delete(RedisKeyConstant.taskCancel(taskId));
        log.info("任务已删除 taskId={} operatorTenant={}", taskId, TenantContextUtil.getTenantId());
    }

    @Override
    public int purgeTenant(String tenantId) {
        if (!TenantContextUtil.isAdmin()) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "仅管理员可清理租户数据");
        }
        List<PrdTask> tasks = prdTaskMapper.selectList(new LambdaQueryWrapper<PrdTask>()
                .eq(PrdTask::getTenantId, tenantId));
        for (PrdTask task : tasks) {
            ragService.dropTaskIndex(task.getTaskId());
            stringRedisTemplate.delete(RedisKeyConstant.taskContext(task.getTaskId()));
        }
        prdTaskMapper.delete(new LambdaQueryWrapper<PrdTask>().eq(PrdTask::getTenantId, tenantId));
        fileService.deleteTenantFiles(tenantId);
        log.info("租户数据已清理 tenantId={} 任务数={}", tenantId, tasks.size());
        return tasks.size();
    }

    /**
     * 版本号 = 当前最大值 + 1，依赖 (tenant_id, file_meta_id, version_no) 唯一索引兜底并发冲突
     */
    private void insertWithVersion(PrdTask task, String tenantId, String fileMetaId) {
        for (int attempt = 0; ; attempt++) {
            Integer maxVersion = prdTaskMapper.selectMaxVersionNo(tenantId, fileMetaId);
            task.setVersionNo(maxVersion == null ? 1 : maxVersion + 1);
            task.setTaskId(null);
            try {
                prdTaskMapper.insert(task);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt >= 2) {
                    throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "任务版本号生成冲突，请稍后重试");
                }
                log.warn("任务版本号并发冲突，重试 attempt={} fileMetaId={}", attempt + 1, fileMetaId);
            }
        }
    }

    private String resolveStrategy(String chunkStrategy) {
        String strategy = StrUtil.blankToDefault(chunkStrategy, properties.getChunk().getStrategy());
        if (!ChunkUtil.STRATEGY_FIXED.equalsIgnoreCase(strategy)
                && !ChunkUtil.STRATEGY_SEMANTIC.equalsIgnoreCase(strategy)) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID,
                    "分块策略仅支持 fixed / semantic，当前值: " + strategy);
        }
        return strategy;
    }

    private String normalizeFormat(String format) {
        String value = StrUtil.blankToDefault(format, "md").toLowerCase();
        return switch (value) {
            case "md", "markdown" -> "md";
            case "html" -> "html";
            case "docx", "doc" -> "docx";
            default -> throw new BizException(BizErrorCodeEnum.PARAM_INVALID,
                    "导出格式仅支持 md / html / docx，当前值: " + format);
        };
    }

    private void checkTenant(String tenantId) {
        if (!TenantContextUtil.isAdmin() && !tenantId.equals(TenantContextUtil.requireTenantId())) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "无权访问其他租户数据");
        }
    }

    /**
     * 加载任务并校验租户归属
     */
    private PrdTask loadAccessibleTask(String taskId) {
        PrdTask task = prdTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "任务不存在");
        }
        checkTenant(task.getTenantId());
        return task;
    }

    /**
     * 报告类操作前置校验：任务成功且报告非空
     */
    private void checkExportable(PrdTask task) {
        if (!TaskStatusEnum.SUCCESS.getCode().equals(task.getTaskStatus())
                || StrUtil.isBlank(task.getResultMarkdown())) {
            throw new BizException(BizErrorCodeEnum.TASK_STATE_ERROR, "任务尚未解析成功，暂无报告内容");
        }
    }
}
