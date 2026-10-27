package com.dbappsecurity.prd2api.event;

import com.dbappsecurity.prd2api.util.MetricUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 任务事件监听：指标埋点 + 告警预留
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEventListener {

    private final MetricUtil metricUtil;

    @EventListener
    public void onSuccess(TaskSuccessEvent event) {
        metricUtil.taskSuccess();
        log.info("任务解析成功 taskId={} tenantId={} userKey={} cost={}ms",
                event.getTaskId(), event.getTenantId(), event.getUserKey(), event.getCostMillis());
        // 预留：企业微信/飞书机器人通知、告警平台上报
    }

    @EventListener
    public void onFail(TaskFailEvent event) {
        metricUtil.taskFail();
        log.error("任务解析失败 taskId={} tenantId={} userKey={} error={}",
                event.getTaskId(), event.getTenantId(), event.getUserKey(), event.getErrorMsg());
        // 预留：告警平台上报（任务失败率、LLM 调用异常）
    }
}
