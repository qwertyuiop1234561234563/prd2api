package com.dbappsecurity.prd2api.vo;

import com.dbappsecurity.prd2api.entity.VipPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * VIP 套餐视图
 */
@Data
@Schema(description = "VIP 套餐视图")
public class VipPlanVO {

    @Schema(description = "套餐编码")
    private String planCode;

    @Schema(description = "套餐名称")
    private String planName;

    @Schema(description = "套餐等级")
    private Integer level;

    @Schema(description = "单次开通有效天数")
    private Integer durationDays;

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

    @Schema(description = "参考价格，单位分")
    private Integer priceCent;

    @Schema(description = "套餐说明")
    private String description;

    @Schema(description = "1 上架 0 下架")
    private Integer status;

    @Schema(description = "是否可开通")
    private Boolean grantable;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    public static VipPlanVO from(VipPlan plan) {
        VipPlanVO vo = new VipPlanVO();
        vo.setPlanCode(plan.getPlanCode());
        vo.setPlanName(plan.getPlanName());
        vo.setLevel(plan.getLevel());
        vo.setDurationDays(plan.getDurationDays());
        vo.setDailyUploadLimit(plan.getDailyUploadLimit());
        vo.setDailyTokenLimit(plan.getDailyTokenLimit());
        vo.setConcurrentTaskLimit(plan.getConcurrentTaskLimit());
        vo.setMaxFileSizeMb(plan.getMaxFileSizeMb());
        vo.setModelTier(plan.getModelTier());
        vo.setPriceCent(plan.getPriceCent());
        vo.setDescription(plan.getDescription());
        vo.setStatus(plan.getStatus());
        vo.setGrantable(plan.grantable());
        vo.setUpdateTime(plan.getUpdateTime());
        return vo;
    }
}
