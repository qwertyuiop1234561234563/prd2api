package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 任务按日统计
 */
@Data
@Schema(description = "任务按日统计")
public class TaskDailyStatVO {

    @Schema(description = "日期 yyyy-MM-dd")
    private String day;

    @Schema(description = "当日任务数")
    private Long total;

    @Schema(description = "当日成功数")
    private Long success;

    @Schema(description = "当日失败数")
    private Long fail;
}
