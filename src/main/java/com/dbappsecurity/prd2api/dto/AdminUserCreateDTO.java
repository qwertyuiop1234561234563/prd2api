package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员创建用户入参
 */
@Data
@Schema(description = "管理员创建用户入参")
public class AdminUserCreateDTO {

    @NotBlank(message = "tenantId 不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "租户ID 仅允许字母、数字、下划线、中划线，长度 1~64")
    @Schema(description = "所属租户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantId;

    @NotBlank(message = "账号不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_.@-]{3,64}$", message = "账号仅允许字母、数字及 _ . @ - ，长度 3~64")
    @Schema(description = "登录账号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需为 8~64 位")
    @Schema(description = "初始密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @Size(max = 64, message = "昵称最长 64 字符")
    @Schema(description = "显示名，留空默认使用账号")
    private String displayName;

    @Pattern(regexp = "^(ADMIN|USER)$", message = "角色仅支持 ADMIN 或 USER")
    @Schema(description = "角色，默认 USER", defaultValue = "USER")
    private String role;
}
