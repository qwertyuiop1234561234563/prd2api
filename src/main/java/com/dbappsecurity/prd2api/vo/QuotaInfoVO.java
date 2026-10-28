package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 当前生效额度视图
 */
@Data
@Schema(description = "当前生效额度视图")
public class QuotaInfoVO {

    public static final String SOURCE_VIP = "VIP";
    public static final String SOURCE_TENANT = "TENANT";

    @Schema(description = "额度来源 VIP / TENANT")
    private String source;

    @Schema(description = "套餐编码")
    private String planCode;

    @Schema(description = "套餐名称")
    private String planName;

    @Schema(description = "每日上传次数上限")
    private Integer dailyUploadLimit;

    @Schema(description = "每日 token 上限")
    private Long dailyTokenLimit;

    @Schema(description = "最大并发任务数")
    private Integer concurrentTaskLimit;

    @Schema(description = "单文件大小上限 MB")
    private Integer maxFileSizeMb;

    @Schema(description = "今日已上传次数")
    private Integer usedUploadCount;

    @Schema(description = "今日已消耗 token")
    private Long usedTokenCount;

    @Schema(description = "VIP 到期时间")
    private LocalDateTime expireTime;
}
