package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * VIP 套餐
 * <p>
 * level = 0（FREE）仅用于展示，duration_days = 0 表示不可开通。
 */
@Data
@TableName("vip_plan")
public class VipPlan {

    @TableId(value = "plan_code", type = IdType.INPUT)
    private String planCode;

    private String planName;

    private Integer level;

    private Integer durationDays;

    private Integer dailyUploadLimit;

    private Long dailyTokenLimit;

    private Integer concurrentTaskLimit;

    private Integer maxFileSizeMb;

    private String modelTier;

    private Integer priceCent;

    private String description;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 是否允许开通（FREE 或 天数为 0 的套餐不允许开通）
     */
    public boolean grantable() {
        return level != null && level > 0 && durationDays != null && durationDays > 0;
    }
}
