package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登录结果
 */
@Data
@Schema(description = "登录结果")
public class LoginVO {

    @Schema(description = "访问令牌，后续请求使用 Authorization: Bearer <token>")
    private String token;

    @Schema(description = "令牌有效期（秒）")
    private Long expireSeconds;

    @Schema(description = "登录用户信息")
    private UserVO user;
}
