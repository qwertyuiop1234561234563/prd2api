package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 登录入参
 */
@Data
@Schema(description = "登录入参")
public class LoginDTO {

    @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "租户ID 仅允许字母、数字、下划线、中划线，长度 1~64")
    @Schema(description = "租户ID，留空默认 default", example = "default")
    private String tenantId;

    @NotBlank(message = "账号不能为空")
    @Schema(description = "登录账号", requiredMode = Schema.RequiredMode.REQUIRED, example = "alice")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "登录密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}
