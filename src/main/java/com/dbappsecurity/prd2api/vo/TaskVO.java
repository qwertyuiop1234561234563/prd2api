package com.dbappsecurity.prd2api.vo;

import com.dbappsecurity.prd2api.entity.PrdTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务视图
 */
@Data
@Schema(description = "任务视图")
public class TaskVO {

    @Schema(description = "任务ID")
    private String taskId;

    @Schema(description = "租户ID")
    private String tenantId;

    @Schema(description = "用户标识")
    private String userKey;

    @Schema(description = "文件元数据ID")
    private String fileMetaId;

    @Schema(description = "文档版本号")
    private Integer versionNo;

    @Schema(description = "任务状态 INIT/PROCESS/SUCCESS/FAIL/CANCELED")
    private String taskStatus;

    @Schema(description = "任务进度 0~100")
    private Integer progress;

    @Schema(description = "评审报告 Markdown，列表场景可为空")
    private String resultMarkdown;

    @Schema(description = "失败原因")
    private String errorMsg;

    @Schema(description = "模型编码")
    private String modelCode;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "完成时间")
    private LocalDateTime finishTime;

    public static TaskVO from(PrdTask task) {
        if (task == null) {
            return null;
        }
        TaskVO vo = new TaskVO();
        vo.setTaskId(task.getTaskId());
        vo.setTenantId(task.getTenantId());
        vo.setUserKey(task.getUserKey());
        vo.setFileMetaId(task.getFileMetaId());
        vo.setVersionNo(task.getVersionNo());
        vo.setTaskStatus(task.getTaskStatus());
        vo.setProgress(task.getProgress());
        vo.setResultMarkdown(task.getResultMarkdown());
        vo.setErrorMsg(task.getErrorMsg());
        vo.setModelCode(task.getModelCode());
        vo.setCreateTime(task.getCreateTime());
        vo.setFinishTime(task.getFinishTime());
        return vo;
    }

    /**
     * 列表场景不返回大字段，减少带宽
     */
    public static TaskVO summary(PrdTask task) {
        TaskVO vo = from(task);
        if (vo != null) {
            vo.setResultMarkdown(null);
        }
        return vo;
    }
}
