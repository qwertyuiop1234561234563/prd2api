package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户（登录账号）
 * <p>
 * VIP 生效时优先使用用户级套餐额度，未开通或已过期则回退所属租户配额。
 */
@Data
@TableName("sys_user")
public class SysUser {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;

    private String tenantId;

    private String username;

    private String passwordHash;

    private String displayName;

    private String role;

    private String status;

    private String vipPlanCode;

    private LocalDateTime vipExpireTime;

    private LocalDateTime lastLoginTime;

    private Integer failCount;

    private LocalDateTime lockUntil;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;
}
