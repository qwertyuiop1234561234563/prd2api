package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.TaskStatusEnum;
import com.dbappsecurity.prd2api.entity.PrdTask;
import com.dbappsecurity.prd2api.mapper.PrdTaskMapper;
import com.dbappsecurity.prd2api.service.TaskService;
import com.dbappsecurity.prd2api.service.TaskStreamService;
import com.dbappsecurity.prd2api.vo.TaskVO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 SSE 的任务进度推送实现
 * <p>
 * 设计取舍：不引入 WebSocket，任务进度已落库，用独立调度线程按秒轮询即可，
 * 与业务线程池隔离，避免 LLM 线程被长连接占用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskStreamServiceImpl implements TaskStreamService {

    private static final long TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long POLL_INTERVAL_MS = 1000L;

    private final PrdTaskMapper prdTaskMapper;
    private final TaskService taskService;
    private final Prd2ApiProperties properties;

    private ScheduledExecutorService executor;
    private Semaphore slots;

    @PostConstruct
    public void init() {
        int threads = Math.max(1, properties.getAsync().getStreamThreads());
        executor = Executors.newScheduledThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "prd2api-task-stream");
            thread.setDaemon(true);
            return thread;
        });
        slots = new Semaphore(Math.max(1, properties.getAsync().getStreamMaxConnections()));
        log.info("SSE 推送池初始化完成 threads={} maxConnections={}", threads, slots.availablePermits());
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public SseEmitter stream(String taskId) {
        // 请求线程内完成租户校验，轮询线程不再依赖 ThreadLocal 上下文
        taskService.checkTaskAccess(taskId);

        if (!slots.tryAcquire()) {
            SseEmitter rejected = new SseEmitter(0L);
            finishWithError(rejected, new AtomicBoolean(false), "SSE 连接数已达上限，请稍后重试");
            return rejected;
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicBoolean released = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                slots.release();
            }
        };
        emitter.onCompletion(() -> {
            done.set(true);
            cancel(future.get());
            release.run();
        });
        emitter.onTimeout(() -> {
            done.set(true);
            completeQuietly(emitter);
            release.run();
        });
        emitter.onError(error -> {
            done.set(true);
            cancel(future.get());
            release.run();
        });

        if (!pushOnce(taskId, emitter, done)) {
            future.set(executor.scheduleAtFixedRate(() -> poll(taskId, emitter, done),
                    POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS));
            // 极端情况下连接在调度前已关闭，避免遗留空轮询任务
            if (done.get()) {
                cancel(future.get());
            }
        }
        return emitter;
    }

    /**
     * 进度推送只读取摘要字段：每秒轮询不能把 result_markdown 大字段一起拉出来
     */
    private PrdTask loadSummary(String taskId) {
        return prdTaskMapper.selectOne(new LambdaQueryWrapper<PrdTask>()
                .select(PrdTask::getTaskId, PrdTask::getTenantId, PrdTask::getUserKey,
                        PrdTask::getFileMetaId, PrdTask::getVersionNo, PrdTask::getTaskStatus,
                        PrdTask::getProgress, PrdTask::getErrorMsg, PrdTask::getModelCode,
                        PrdTask::getCreateTime, PrdTask::getFinishTime)
                .eq(PrdTask::getTaskId, taskId));
    }

    private void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 立即推送一次当前状态
     *
     * @return true 表示任务已终态（或连接不可用），无需继续轮询
     */
    private boolean pushOnce(String taskId, SseEmitter emitter, AtomicBoolean done) {
        PrdTask task = loadSummary(taskId);
        if (task == null) {
            finishWithError(emitter, done, "任务不存在");
            return true;
        }
        return finishIfTerminal(push(task, emitter, done), emitter, done);
    }

    private void poll(String taskId, SseEmitter emitter, AtomicBoolean done) {
        if (done.get()) {
            return;
        }
        try {
            PrdTask task = loadSummary(taskId);
            if (task == null) {
                finishWithError(emitter, done, "任务已删除");
                return;
            }
            finishIfTerminal(push(task, emitter, done), emitter, done);
        } catch (Exception e) {
            log.debug("任务进度推送结束 taskId={} reason={}", taskId, e.getMessage());
            done.set(true);
            completeQuietly(emitter);
        }
    }

    private boolean finishIfTerminal(boolean terminal, SseEmitter emitter, AtomicBoolean done) {
        if (terminal) {
            if (done.compareAndSet(false, true)) {
                completeQuietly(emitter);
            }
        }
        return terminal;
    }

    /**
     * 推送一条进度事件
     *
     * @return true 表示任务已是终态，或连接已不可用
     */
    private boolean push(PrdTask task, SseEmitter emitter, AtomicBoolean done) {
        try {
            TaskVO vo = TaskVO.summary(task);
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .reconnectTime(3000L)
                    .data(vo, MediaType.APPLICATION_JSON));
            return isTerminal(task.getTaskStatus());
        } catch (Exception e) {
            // 客户端断开或连接已关闭
            done.set(true);
            log.debug("SSE 连接已关闭 taskId={}", task.getTaskId());
            return true;
        }
    }

    private void finishWithError(SseEmitter emitter, AtomicBoolean done, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message, MediaType.TEXT_PLAIN));
        } catch (Exception e) {
            log.debug("SSE 错误事件发送失败 message={}", message);
        }
        if (done.compareAndSet(false, true)) {
            completeQuietly(emitter);
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE 关闭连接异常: {}", e.getMessage());
        }
    }

    private boolean isTerminal(String status) {
        return TaskStatusEnum.SUCCESS.getCode().equals(status)
                || TaskStatusEnum.FAIL.getCode().equals(status)
                || TaskStatusEnum.CANCELED.getCode().equals(status)
                || StrUtil.isBlank(status);
    }
}
