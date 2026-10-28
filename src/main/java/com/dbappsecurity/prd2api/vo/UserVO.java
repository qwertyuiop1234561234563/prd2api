package com.dbappsecurity.prd2api.vo;

import com.dbappsecurity.prd2api.entity.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户视图
 */
@Data
@Schema(description = "用户视图")
public class UserVO {

    @Schema(description = "用户ID")
    private String userId;

    @Schema(description = "租户ID")
    private String tenantId;

    @Schema(description = "登录账号")
    private String username;

    @Schema(description = "显示名")
    private String displayName;

    @Schema(description = "角色 ADMIN / USER")
    private String role;

    @Schema(description = "账号状态 ACTIVE / DISABLED")
    private String status;

    @Schema(description = "当前套餐编码")
    private String vipPlanCode;

    @Schema(description = "当前套餐名称")
    private String vipPlanName;

    @Schema(description = "VIP 到期时间")
    private LocalDateTime vipExpireTime;

    @Schema(description = "VIP 是否生效中")
    private Boolean vipActive;

    @Schema(description = "最近登录时间")
    private LocalDateTime lastLoginTime;

    @Schema(description = "连续登录失败次数")
    private Integer failCount;

    @Schema(description = "登录锁定截止时间")
    private LocalDateTime lockUntil;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public static UserVO from(SysUser user, String planName, boolean vipActive) {
        UserVO vo = new UserVO();
        vo.setUserId(user.getUserId());
        vo.setTenantId(user.getTenantId());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setVipPlanCode(user.getVipPlanCode());
        vo.setVipPlanName(planName);
        vo.setVipExpireTime(user.getVipExpireTime());
        vo.setVipActive(vipActive);
        vo.setLastLoginTime(user.getLastLoginTime());
        vo.setFailCount(user.getFailCount());
        vo.setLockUntil(user.getLockUntil());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }
}
