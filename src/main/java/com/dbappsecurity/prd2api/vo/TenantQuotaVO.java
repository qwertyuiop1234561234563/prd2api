package com.dbappsecurity.prd2api.vo;

import com.dbappsecurity.prd2api.entity.TenantQuota;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户配额视图
 */
@Data
@Schema(description = "租户配额视图")
public class TenantQuotaVO {

    @Schema(description = "租户ID")
    private String tenantId;

    @Schema(description = "每日上传次数上限")
    private Integer dailyUploadLimit;

    @Schema(description = "每日 token 上限")
    private Long dailyTokenLimit;

    @Schema(description = "最大并发任务数")
    private Integer concurrentTaskLimit;

    @Schema(description = "今日已上传次数")
    private Integer usedUploadCount;

    @Schema(description = "今日已消耗 token")
    private Long usedTokenCount;

    @Schema(description = "配额重置时间")
    private LocalDateTime resetTime;

    public static TenantQuotaVO from(TenantQuota quota) {
        TenantQuotaVO vo = new TenantQuotaVO();
        vo.setTenantId(quota.getTenantId());
        vo.setDailyUploadLimit(quota.getDailyUploadLimit());
        vo.setDailyTokenLimit(quota.getDailyTokenLimit());
        vo.setConcurrentTaskLimit(quota.getConcurrentTaskLimit());
        vo.setUsedUploadCount(quota.getUsedUploadCount());
        vo.setUsedTokenCount(quota.getUsedTokenCount());
        vo.setResetTime(quota.getResetTime());
        return vo;
    }
}
