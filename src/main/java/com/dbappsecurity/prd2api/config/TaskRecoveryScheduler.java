package com.dbappsecurity.prd2api.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.constant.TaskStatusEnum;
import com.dbappsecurity.prd2api.entity.PrdTask;
import com.dbappsecurity.prd2api.mapper.PrdTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 僵死任务回收
 * <p>
 * 进程崩溃、重启或线程池异常退出后，任务会长期停留在 INIT / PROCESS：
 * 前端一直转圈，且并发配额按 PROCESS 计数会被一直占用。这里周期性扫描，
 * 用 Redis 任务锁判断是否真的有实例在跑：锁存在说明任务活跃（可能在其他节点），跳过；
 * 锁不存在的才判定为中断并置为 FAIL，用户可自行重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRecoveryScheduler {

    /** 单批回收上限，避免一次扫描过多任务拖慢启动 */
    private static final int BATCH_SIZE = 200;
    /** 回写失败原因，明确告知前端需重新发起 */
    private static final String RECOVER_ERROR_MSG = "任务执行中断（服务重启或实例异常），请重新发起";

    private final PrdTaskMapper prdTaskMapper;
    private final RedissonClient redissonClient;

    @Value("${prd2api.task.recover-enabled:true}")
    private boolean recoverEnabled;

    @Value("${prd2api.task.recover-stale-minutes:5}")
    private int staleMinutes;

    /**
     * 启动后先执行一次，回收上一次进程遗留的任务；之后按固定间隔巡检
     */
    @Scheduled(initialDelayString = "${prd2api.task.recover-initial-delay-ms:20000}",
            fixedDelayString = "${prd2api.task.recover-interval-ms:300000}")
    public void recoverStaleTasks() {
        if (!recoverEnabled) {
            return;
        }
        try {
            doRecover();
        } catch (Exception e) {
            log.error("僵死任务回收失败", e);
        }
    }

    private void doRecover() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(Math.max(1, staleMinutes));
        List<PrdTask> candidates = prdTaskMapper.selectList(new LambdaQueryWrapper<PrdTask>()
                .in(PrdTask::getTaskStatus,
                        TaskStatusEnum.INIT.getCode(), TaskStatusEnum.PROCESS.getCode())
                .lt(PrdTask::getUpdateTime, threshold)
                .orderByAsc(PrdTask::getUpdateTime)
                .last("limit " + BATCH_SIZE));
        if (candidates.isEmpty()) {
            return;
        }
        int recovered = 0;
        for (PrdTask task : candidates) {
            String lockKey = RedisKeyConstant.taskLock(task.getTaskId());
            if (redissonClient.getLock(lockKey).isLocked()) {
                continue;
            }
            int rows = prdTaskMapper.update(null, new LambdaUpdateWrapper<PrdTask>()
                    .eq(PrdTask::getTaskId, task.getTaskId())
                    .in(PrdTask::getTaskStatus,
                            TaskStatusEnum.INIT.getCode(), TaskStatusEnum.PROCESS.getCode())
                    .set(PrdTask::getTaskStatus, TaskStatusEnum.FAIL.getCode())
                    .set(PrdTask::getErrorMsg, RECOVER_ERROR_MSG)
                    .set(PrdTask::getFinishTime, LocalDateTime.now()));
            if (rows > 0) {
                recovered++;
                log.warn("回收僵死任务 taskId={} tenantId={} lastStatus={} updateTime={}",
                        task.getTaskId(), task.getTenantId(), task.getTaskStatus(), task.getUpdateTime());
            }
        }
        if (recovered > 0) {
            log.warn("僵死任务回收完成 candidates={} recovered={}", candidates.size(), recovered);
        }
    }
}
