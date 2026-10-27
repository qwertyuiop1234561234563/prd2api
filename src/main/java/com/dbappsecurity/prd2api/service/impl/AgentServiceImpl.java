package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dbappsecurity.prd2api.agent.dto.AgentContext;
import com.dbappsecurity.prd2api.agent.service.Stage1BusinessUnderstandService;
import com.dbappsecurity.prd2api.agent.service.Stage2RiskScanService;
import com.dbappsecurity.prd2api.agent.service.Stage3StructureGenerateService;
import com.dbappsecurity.prd2api.agent.service.Stage4SummaryService;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.AsyncThreadPoolConfig;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.AgentPromptConstant;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.constant.TaskStatusEnum;
import com.dbappsecurity.prd2api.entity.FileMeta;
import com.dbappsecurity.prd2api.entity.PrdTask;
import com.dbappsecurity.prd2api.entity.PrdTaskCache;
import com.dbappsecurity.prd2api.event.TaskFailEvent;
import com.dbappsecurity.prd2api.event.TaskSuccessEvent;
import com.dbappsecurity.prd2api.mapper.FileMetaMapper;
import com.dbappsecurity.prd2api.mapper.PrdTaskCacheMapper;
import com.dbappsecurity.prd2api.mapper.PrdTaskMapper;
import com.dbappsecurity.prd2api.service.AgentService;
import com.dbappsecurity.prd2api.service.FileService;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import com.dbappsecurity.prd2api.service.RagService;
import com.dbappsecurity.prd2api.util.ContextCompressUtil;
import com.dbappsecurity.prd2api.util.RedisLockUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.util.TraceIdUtil;
import com.dbappsecurity.prd2api.vo.RagChunkVO;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 流水线实现：固定 4 轮串行，含分布式锁、进度更新、失败标记与事件发布
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private static final int ERROR_MSG_MAX_LENGTH = 900;

    private final PrdTaskMapper prdTaskMapper;
    private final PrdTaskCacheMapper prdTaskCacheMapper;
    private final FileMetaMapper fileMetaMapper;
    private final FileService fileService;
    private final RagService ragService;
    private final LlmInvokeService llmInvokeService;
    private final Stage1BusinessUnderstandService stage1Service;
    private final Stage2RiskScanService stage2Service;
    private final Stage3StructureGenerateService stage3Service;
    private final Stage4SummaryService stage4Service;
    private final RedisLockUtil redisLockUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final Prd2ApiProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * LLM 调用线程池：字段注入按 Bean 名称解析，避免两个线程池歧义
     */
    @Resource(name = AsyncThreadPoolConfig.LLM_EXECUTOR)
    private ThreadPoolTaskExecutor llmExecutor;

    @Override
    public void executeAsync(String taskId) {
        llmExecutor.execute(() -> {
            try {
                execute(taskId);
            } catch (Exception e) {
                log.error("异步任务执行异常 taskId={}", taskId, e);
                updateFail(taskId, StrUtil.maxLength(e.getMessage(), ERROR_MSG_MAX_LENGTH));
            }
        });
    }

    @Override
    public void execute(String taskId) {
        PrdTask task = prdTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务不存在，跳过执行 taskId={}", taskId);
            return;
        }
        String lockKey = RedisKeyConstant.taskLock(taskId);
        // 看门狗续约：流水线耗时不可预估，避免固定租期到期后同一任务被重复执行
        if (!redisLockUtil.tryLock(lockKey, 0)) {
            log.warn("任务已在执行中，忽略重复触发 taskId={}", taskId);
            return;
        }

        TraceIdUtil.setTraceId(null);
        TenantContextUtil.set(task.getTenantId(), task.getUserKey(), TenantContextUtil.USER_ROLE);
        long start = System.currentTimeMillis();
        try {
            if (isCanceled(taskId) || TaskStatusEnum.CANCELED.getCode().equals(task.getTaskStatus())) {
                updateCanceled(taskId);
                log.info("任务已取消，跳过执行 taskId={}", taskId);
                return;
            }
            updateProgress(taskId, 5);
            FileMeta meta = fileMetaMapper.selectById(task.getFileMetaId());
            if (meta == null) {
                throw new BizException(BizErrorCodeEnum.NOT_FOUND, "源文件不存在或已被清理");
            }
            String rawText = fileService.loadText(meta);
            int maxChars = Math.max(2000, properties.getChunk().getMaxLlmChars());
            boolean compressed = ContextCompressUtil.exceeds(rawText, maxChars);
            String docText = ContextCompressUtil.compress(rawText, maxChars);
            String strategy = resolveChunkStrategy(taskId);

            updateProgress(taskId, 12);
            ragService.buildTaskIndex(task.getTenantId(), taskId, rawText, strategy);
            updateProgress(taskId, 22);
            List<RagChunkVO> chunks = ragService.retrieve(taskId,
                    "业务目标 业务实体 核心流程 字段 接口", properties.getRag().getTopK());

            AgentContext context = AgentContext.builder()
                    .taskId(taskId)
                    .tenantId(task.getTenantId())
                    .userKey(task.getUserKey())
                    .modelCode(task.getModelCode())
                    .chunkStrategy(strategy)
                    .docText(docText)
                    .docCompressed(compressed)
                    .chunks(chunks)
                    .build();

            context.setStage1Output(stage1Service.execute(context));
            updateProgress(taskId, 40);
            if (stopIfCanceled(taskId)) {
                return;
            }
            context.setStage2Output(stage2Service.execute(context));
            updateProgress(taskId, 58);
            if (stopIfCanceled(taskId)) {
                return;
            }
            context.setStage3Output(stage3Service.execute(context));
            updateProgress(taskId, 74);
            if (stopIfCanceled(taskId)) {
                return;
            }
            String report = stage4Service.execute(context);
            if (StrUtil.isBlank(report)) {
                throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "阶段4输出为空，评审报告生成失败");
            }
            if (stopIfCanceled(taskId)) {
                return;
            }

            updateSuccess(taskId, report);
            writeCache(task, meta, strategy, report);
            long cost = System.currentTimeMillis() - start;
            eventPublisher.publishEvent(new TaskSuccessEvent(taskId, task.getTenantId(), task.getUserKey(), cost));
            log.info("任务解析完成 taskId={} cost={}ms reportLength={}", taskId, cost, report.length());
        } catch (Exception e) {
            String errorMsg = StrUtil.maxLength(
                    StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), ERROR_MSG_MAX_LENGTH);
            log.error("任务解析失败 taskId={} error={}", taskId, errorMsg, e);
            updateFail(taskId, errorMsg);
            eventPublisher.publishEvent(new TaskFailEvent(taskId, task.getTenantId(), task.getUserKey(), errorMsg));
        } finally {
            redisLockUtil.unlock(lockKey);
            TenantContextUtil.clear();
            TraceIdUtil.clear();
        }
    }

    @Override
    public String chat(String taskId, String question) {
        PrdTask task = prdTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "任务不存在");
        }
        checkTenant(task.getTenantId());
        if (!TaskStatusEnum.SUCCESS.getCode().equals(task.getTaskStatus())) {
            throw new BizException(BizErrorCodeEnum.TASK_STATE_ERROR, "仅解析成功的任务支持追问");
        }
        String lockKey = RedisKeyConstant.taskLock(taskId);
        if (!redisLockUtil.tryLock(lockKey, 3, 60)) {
            throw new BizException(BizErrorCodeEnum.TASK_STATE_ERROR, "任务正在处理中，请稍后追问");
        }
        try {
            List<RagChunkVO> chunks = ragService.retrieve(taskId, question, properties.getRag().getTopK());
            StringBuilder contextText = new StringBuilder();
            for (RagChunkVO chunk : chunks) {
                contextText.append('[').append(chunk.getSource()).append("] ").append(chunk.getContent()).append('\n');
            }
            int promptBudget = Math.max(4000, properties.getChunk().getMaxLlmChars() / 2);
            String report = ContextCompressUtil.compress(StrUtil.nullToEmpty(task.getResultMarkdown()), promptBudget);
            String userPrompt = AgentPromptConstant.FOLLOW_UP_PROMPT
                    + "\n【已有评审报告】\n" + report
                    + "\n\n【原文召回片段】\n" + contextText
                    + "\n\n【本次追问】\n" + question;
            String answer = llmInvokeService.chat(AgentPromptConstant.GLOBAL_SYSTEM_PROMPT,
                    userPrompt, task.getModelCode());
            String merged = StrUtil.nullToEmpty(task.getResultMarkdown())
                    + "\n\n---\n\n## 追问补充\n\n**Q：** " + question + "\n\n**A：** " + answer + "\n";
            prdTaskMapper.update(null, new LambdaUpdateWrapper<PrdTask>()
                    .eq(PrdTask::getTaskId, taskId)
                    .set(PrdTask::getResultMarkdown, merged));
            return answer;
        } finally {
            redisLockUtil.unlock(lockKey);
        }
    }

    private void updateProgress(String taskId, int progress) {
        prdTaskMapper.update(null, new LambdaUpdateWrapper<PrdTask>()
                .eq(PrdTask::getTaskId, taskId)
                .set(PrdTask::getTaskStatus, TaskStatusEnum.PROCESS.getCode())
                .set(PrdTask::getProgress, progress));
    }

    private void updateSuccess(String taskId, String report) {
        prdTaskMapper.update(null, new LambdaUpdateWrapper<PrdTask>()
                .eq(PrdTask::getTaskId, taskId)
                .set(PrdTask::getTaskStatus, TaskStatusEnum.SUCCESS.getCode())
                .set(PrdTask::getProgress, 100)
                .set(PrdTask::getResultMarkdown, report)
                .set(PrdTask::getErrorMsg, null)
                .set(PrdTask::getFinishTime, LocalDateTime.now()));
    }

    private void updateFail(String taskId, String errorMsg) {
        prdTaskMapper.update(null, new LambdaUpdateWrapper<PrdTask>()
                .eq(PrdTask::getTaskId, taskId)
                .set(PrdTask::getTaskStatus, TaskStatusEnum.FAIL.getCode())
                .set(PrdTask::getErrorMsg, errorMsg)
                .set(PrdTask::getFinishTime, LocalDateTime.now()));
    }

    /**
     * 阶段边界检查取消标记，命中则终止流水线
     *
     * @return true 表示已取消，调用方应直接结束
     */
    private boolean stopIfCanceled(String taskId) {
        if (!isCanceled(taskId)) {
            return false;
        }
        updateCanceled(taskId);
        log.info("任务被用户取消，流水线已中断 taskId={}", taskId);
        return true;
    }

    private boolean isCanceled(String taskId) {
        return StrUtil.isNotBlank(
                stringRedisTemplate.opsForValue().get(RedisKeyConstant.taskCancel(taskId)));
    }

    private void updateCanceled(String taskId) {
        prdTaskMapper.update(null, new LambdaUpdateWrapper<PrdTask>()
                .eq(PrdTask::getTaskId, taskId)
                .set(PrdTask::getTaskStatus, TaskStatusEnum.CANCELED.getCode())
                .set(PrdTask::getErrorMsg, "任务已被用户取消")
                .set(PrdTask::getFinishTime, LocalDateTime.now()));
    }

    /**
     * 报告缓存：相同 文件+模型+分块策略 可直接复用，节省 token
     */
    private void writeCache(PrdTask task, FileMeta meta, String strategy, String report) {
        try {
            // 同一 文件+模型+策略 只保留最新一份有效缓存，避免重复执行导致缓存表膨胀
            prdTaskCacheMapper.delete(new LambdaQueryWrapper<PrdTaskCache>()
                    .eq(PrdTaskCache::getTenantId, task.getTenantId())
                    .eq(PrdTaskCache::getFileMd5, meta.getFileMd5())
                    .eq(PrdTaskCache::getModelCode, task.getModelCode())
                    .eq(PrdTaskCache::getChunkStrategy, strategy));
            PrdTaskCache cache = new PrdTaskCache();
            cache.setTenantId(task.getTenantId());
            cache.setFileMd5(meta.getFileMd5());
            cache.setModelCode(task.getModelCode());
            cache.setChunkStrategy(strategy);
            cache.setCacheResultMarkdown(report);
            cache.setExpireTime(LocalDateTime.now().plusDays(Math.max(1, properties.getClean().getExpireDay())));
            prdTaskCacheMapper.insert(cache);
        } catch (Exception e) {
            log.warn("报告缓存写入失败 taskId={}", task.getTaskId(), e);
        }
    }

    private String resolveChunkStrategy(String taskId) {
        String cached = stringRedisTemplate.opsForValue().get(RedisKeyConstant.taskContext(taskId));
        return StrUtil.blankToDefault(cached, properties.getChunk().getStrategy());
    }

    private void checkTenant(String tenantId) {
        if (!TenantContextUtil.isAdmin() && !tenantId.equals(TenantContextUtil.requireTenantId())) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "无权操作其他租户任务");
        }
    }
}
