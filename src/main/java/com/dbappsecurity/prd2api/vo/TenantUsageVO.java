package com.dbappsecurity.prd2api.vo;

import com.dbappsecurity.prd2api.entity.TenantQuota;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 租户资源用量视图
 */
@Data
@Schema(description = "租户资源用量视图")
public class TenantUsageVO {

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

    public static TenantUsageVO from(TenantQuota quota) {
        TenantUsageVO vo = new TenantUsageVO();
        vo.setTenantId(quota.getTenantId());
        vo.setDailyUploadLimit(quota.getDailyUploadLimit());
        vo.setDailyTokenLimit(quota.getDailyTokenLimit());
        vo.setConcurrentTaskLimit(quota.getConcurrentTaskLimit());
        vo.setUsedUploadCount(quota.getUsedUploadCount());
        vo.setUsedTokenCount(quota.getUsedTokenCount());
        return vo;
    }
}
