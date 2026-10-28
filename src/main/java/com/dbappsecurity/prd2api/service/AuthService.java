package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.dto.AdminUserCreateDTO;
import com.dbappsecurity.prd2api.dto.ChangePasswordDTO;
import com.dbappsecurity.prd2api.dto.LoginDTO;
import com.dbappsecurity.prd2api.dto.RegisterDTO;
import com.dbappsecurity.prd2api.entity.SysUser;
import com.dbappsecurity.prd2api.vo.LoginVO;
import com.dbappsecurity.prd2api.vo.UserVO;

/**
 * 认证服务：注册、登录、登出、当前用户、改密
 */
public interface AuthService {

    /**
     * 自助注册（固定 USER 角色），成功后直接返回登录态
     */
    LoginVO register(RegisterDTO dto);

    /**
     * 账号密码登录，连续失败达到上限锁定账号
     */
    LoginVO login(LoginDTO dto);

    /**
     * 登出：删除当前令牌
     */
    void logout(String token);

    /**
     * 当前登录用户信息
     */
    UserVO me();

    /**
     * 修改密码，成功后该用户全部令牌失效
     */
    void changePassword(ChangePasswordDTO dto);

    /**
     * 管理员创建用户（可指定 ADMIN 角色）
     */
    UserVO createUser(AdminUserCreateDTO dto);

    /**
     * 解析登录令牌，无效/过期/账号禁用返回 null，有效则滑动续期
     */
    SysUser resolveToken(String token);
}
