package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 任务总览统计
 */
@Data
@Schema(description = "任务总览统计")
public class TaskStatsVO {

    @Schema(description = "任务总数")
    private Long total;

    @Schema(description = "成功任务数")
    private Long success;

    @Schema(description = "失败任务数")
    private Long fail;

    @Schema(description = "取消任务数")
    private Long canceled;

    @Schema(description = "今日新增任务数")
    private Long todayTotal;

    @Schema(description = "平均解析耗时（秒，基于创建到完成）")
    private Double avgDurationSeconds;

    @Schema(description = "成功率百分比，保留两位小数")
    private Double successRate;
}
