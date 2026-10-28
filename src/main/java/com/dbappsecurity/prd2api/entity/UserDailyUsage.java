package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户每日用量（VIP 额度计数）
 */
@Data
@TableName("user_daily_usage")
public class UserDailyUsage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String userId;

    private LocalDate statDate;

    private Integer uploadCount;

    private Long tokenCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
