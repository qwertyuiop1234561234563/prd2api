package com.dbappsecurity.prd2api.controller;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.annotation.Audit;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.dto.ChangePasswordDTO;
import com.dbappsecurity.prd2api.dto.LoginDTO;
import com.dbappsecurity.prd2api.dto.RegisterDTO;
import com.dbappsecurity.prd2api.service.AuthService;
import com.dbappsecurity.prd2api.vo.LoginVO;
import com.dbappsecurity.prd2api.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册、登录、登出、当前用户、修改密码
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
@Tag(name = "认证", description = "账号密码登录，令牌有效期 12 小时滑动续期")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    @Operation(summary = "注册", description = "自助注册固定为 USER 角色，注册成功直接返回登录令牌")
    @PostMapping("/register")
    public RespModel<LoginVO> register(@Valid @RequestBody RegisterDTO dto) {
        return RespModel.success(authService.register(dto));
    }

    @Operation(summary = "登录", description = "连续失败 5 次锁定 15 分钟；成功后返回 Bearer Token")
    @PostMapping("/login")
    public RespModel<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return RespModel.success(authService.login(dto));
    }

    @Audit("用户登出")
    @Operation(summary = "登出", description = "删除当前令牌，其他端登录态不受影响")
    @PostMapping("/logout")
    public RespModel<Void> logout(HttpServletRequest request) {
        authService.logout(resolveToken(request));
        return RespModel.success();
    }

    @Operation(summary = "当前登录用户", description = "返回账号信息与 VIP 生效状态")
    @GetMapping("/me")
    public RespModel<UserVO> me() {
        return RespModel.success(authService.me());
    }

    @Audit("修改密码")
    @Operation(summary = "修改密码", description = "校验原密码，成功后该用户全部令牌失效，需要重新登录")
    @PostMapping("/password")
    public RespModel<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        authService.changePassword(dto);
        return RespModel.success();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StrUtil.isBlank(header) || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return StrUtil.trim(header.substring(BEARER_PREFIX.length()));
    }
}
