package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * PRD 解析任务
 */
@Data
@TableName("prd_task")
public class PrdTask {

    @TableId(value = "task_id", type = IdType.ASSIGN_UUID)
    private String taskId;

    private String tenantId;

    private String userKey;

    private String fileMetaId;

    private Integer versionNo;

    /** INIT / PROCESS / SUCCESS / FAIL */
    private String taskStatus;

    private Integer progress;

    private String resultMarkdown;

    private String errorMsg;

    private String modelCode;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private LocalDateTime finishTime;

    @TableLogic
    private Integer deleted;
}
