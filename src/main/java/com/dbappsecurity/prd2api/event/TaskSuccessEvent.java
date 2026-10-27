package com.dbappsecurity.prd2api.event;

import lombok.Getter;

/**
 * 任务成功事件：预留对接消息通知、告警平台
 */
@Getter
public class TaskSuccessEvent {

    private final String taskId;
    private final String tenantId;
    private final String userKey;
    private final long costMillis;

    public TaskSuccessEvent(String taskId, String tenantId, String userKey, long costMillis) {
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.userKey = userKey;
        this.costMillis = costMillis;
    }
}
