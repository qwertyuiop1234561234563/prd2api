package com.dbappsecurity.prd2api.event;

import lombok.Getter;

/**
 * 任务失败事件：预留对接告警平台
 */
@Getter
public class TaskFailEvent {

    private final String taskId;
    private final String tenantId;
    private final String userKey;
    private final String errorMsg;

    public TaskFailEvent(String taskId, String tenantId, String userKey, String errorMsg) {
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.userKey = userKey;
        this.errorMsg = errorMsg;
    }
}
