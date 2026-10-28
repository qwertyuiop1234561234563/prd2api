package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的 VIP 视图：套餐额度 + 今日用量
 */
@Data
@Schema(description = "我的 VIP 视图")
public class MyVipVO {

    @Schema(description = "用户ID")
    private String userId;

    @Schema(description = "账号")
    private String username;

    @Schema(description = "套餐编码，未开通为空")
    private String planCode;

    @Schema(description = "套餐名称")
    private String planName;

    @Schema(description = "套餐等级")
    private Integer level;

    @Schema(description = "VIP 是否生效中")
    private Boolean active;

    @Schema(description = "到期时间")
    private LocalDateTime expireTime;

    @Schema(description = "剩余天数，未开通为 0")
    private Long remainDays;

    @Schema(description = "每日上传次数上限")
    private Integer dailyUploadLimit;

    @Schema(description = "每日 token 上限")
    private Long dailyTokenLimit;

    @Schema(description = "最大并发任务数")
    private Integer concurrentTaskLimit;

    @Schema(description = "单文件大小上限 MB")
    private Integer maxFileSizeMb;

    @Schema(description = "可用模型档位")
    private String modelTier;

    @Schema(description = "今日已上传次数")
    private Integer usedUploadCount;

    @Schema(description = "今日已消耗 token")
    private Long usedTokenCount;
}
