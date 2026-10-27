package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端统计大盘
 */
@Data
@Schema(description = "管理端统计大盘")
public class AdminStatsVO {

    @Schema(description = "任务总览")
    private TaskStatsVO overview;

    @Schema(description = "近 N 天按日统计")
    private List<TaskDailyStatVO> daily;

    @Schema(description = "token 消耗 Top 租户")
    private List<TenantUsageVO> topTenants;

    @Schema(description = "统计生成时间")
    private LocalDateTime generatedAt;
}
