package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * VIP 开通记录
 */
@Data
@TableName("user_vip")
public class UserVip {

    public static final String SOURCE_ADMIN = "ADMIN";
    public static final String SOURCE_REDEEM = "REDEEM";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String userId;

    private String planCode;

    private String source;

    private LocalDateTime startTime;

    private LocalDateTime expireTime;

    private String operator;

    private String remark;

    private LocalDateTime createTime;
}
