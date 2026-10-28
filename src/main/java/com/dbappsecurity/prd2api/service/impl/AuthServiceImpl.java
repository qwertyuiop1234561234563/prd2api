package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.dto.AdminUserCreateDTO;
import com.dbappsecurity.prd2api.dto.ChangePasswordDTO;
import com.dbappsecurity.prd2api.dto.LoginDTO;
import com.dbappsecurity.prd2api.dto.RegisterDTO;
import com.dbappsecurity.prd2api.entity.SysUser;
import com.dbappsecurity.prd2api.entity.VipPlan;
import com.dbappsecurity.prd2api.mapper.SysUserMapper;
import com.dbappsecurity.prd2api.mapper.VipPlanMapper;
import com.dbappsecurity.prd2api.service.AuthService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.LoginVO;
import com.dbappsecurity.prd2api.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 认证实现：BCrypt 密码 + Redis 不透明 Token（滑动续期）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_TENANT = "default";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysUserMapper sysUserMapper;
    private final VipPlanMapper vipPlanMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final Prd2ApiProperties properties;

    @Override
    public LoginVO register(RegisterDTO dto) {
        String tenantId = StrUtil.blankToDefault(dto.getTenantId(), DEFAULT_TENANT);
        checkSelfRegisterAllowed(tenantId);
        checkUsernameAvailable(tenantId, dto.getUsername());
        SysUser user = new SysUser();
        user.setUserId(IdUtil.fastSimpleUUID());
        user.setTenantId(tenantId);
        user.setUsername(dto.getUsername());
        user.setPasswordHash(BCrypt.hashpw(dto.getPassword()));
        user.setDisplayName(StrUtil.blankToDefault(dto.getDisplayName(), dto.getUsername()));
        user.setRole(TenantContextUtil.USER_ROLE);
        user.setStatus(SysUser.STATUS_ACTIVE);
        user.setFailCount(0);
        user.setDeleted(0);
        try {
            sysUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "账号已存在");
        }
        log.info("用户注册成功 userId={} tenantId={} username={}", user.getUserId(), tenantId, user.getUsername());
        return issueToken(user);
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        String tenantId = StrUtil.blankToDefault(dto.getTenantId(), DEFAULT_TENANT);
        SysUser user = sysUserMapper.selectByTenantAndUsername(tenantId, dto.getUsername());
        if (user == null) {
            throw new BizException(BizErrorCodeEnum.UNAUTHORIZED, "账号或密码错误");
        }
        if (!SysUser.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "账号已被禁用，请联系管理员");
        }
        LocalDateTime now = LocalDateTime.now();
        if (user.getLockUntil() != null && user.getLockUntil().isAfter(now)) {
            throw new BizException(BizErrorCodeEnum.ACCOUNT_LOCKED,
                    "账号已锁定，请于 " + user.getLockUntil().format(TIME_FORMAT) + " 后重试");
        }
        if (!BCrypt.checkpw(dto.getPassword(), user.getPasswordHash())) {
            handleLoginFail(user);
        }
        recordLoginSuccess(user);
        refreshExpiredVip(user);
        log.info("用户登录成功 userId={} tenantId={} username={}", user.getUserId(), tenantId, user.getUsername());
        return issueToken(user);
    }

    @Override
    public void logout(String token) {
        if (StrUtil.isBlank(token)) {
            return;
        }
        String userId = stringRedisTemplate.opsForValue().get(RedisKeyConstant.authToken(token));
        stringRedisTemplate.delete(RedisKeyConstant.authToken(token));
        if (StrUtil.isNotBlank(userId)) {
            stringRedisTemplate.opsForSet().remove(RedisKeyConstant.authUserTokens(userId), token);
            log.info("用户登出 userId={}", userId);
        }
    }

    @Override
    public UserVO me() {
        SysUser user = requireCurrentUser();
        refreshExpiredVip(user);
        return toUserVO(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(ChangePasswordDTO dto) {
        SysUser user = requireCurrentUser();
        if (!BCrypt.checkpw(dto.getOldPassword(), user.getPasswordHash())) {
            throw new BizException(BizErrorCodeEnum.UNAUTHORIZED, "原密码不正确");
        }
        if (StrUtil.equals(dto.getOldPassword(), dto.getNewPassword())) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "新密码不能与原密码相同");
        }
        user.setPasswordHash(BCrypt.hashpw(dto.getNewPassword()));
        sysUserMapper.updateById(user);
        revokeAllTokens(user.getUserId());
        log.info("用户修改密码 userId={}", user.getUserId());
    }

    @Override
    public UserVO createUser(AdminUserCreateDTO dto) {
        checkUsernameAvailable(dto.getTenantId(), dto.getUsername());
        SysUser user = new SysUser();
        user.setUserId(IdUtil.fastSimpleUUID());
        user.setTenantId(dto.getTenantId());
        user.setUsername(dto.getUsername());
        user.setPasswordHash(BCrypt.hashpw(dto.getPassword()));
        user.setDisplayName(StrUtil.blankToDefault(dto.getDisplayName(), dto.getUsername()));
        user.setRole(StrUtil.blankToDefault(dto.getRole(), TenantContextUtil.USER_ROLE));
        user.setStatus(SysUser.STATUS_ACTIVE);
        user.setFailCount(0);
        user.setDeleted(0);
        try {
            sysUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "账号已存在");
        }
        log.info("管理员创建用户 userId={} tenantId={} role={} operator={}",
                user.getUserId(), user.getTenantId(), user.getRole(), TenantContextUtil.getUserKey());
        return toUserVO(user);
    }

    @Override
    public SysUser resolveToken(String token) {
        if (StrUtil.isBlank(token)) {
            return null;
        }
        String tokenKey = RedisKeyConstant.authToken(token);
        String userId = stringRedisTemplate.opsForValue().get(tokenKey);
        if (StrUtil.isBlank(userId)) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || !SysUser.STATUS_ACTIVE.equals(user.getStatus())) {
            stringRedisTemplate.delete(tokenKey);
            return null;
        }
        long ttl = properties.getAuth().getTokenTtlSeconds();
        stringRedisTemplate.expire(tokenKey, ttl, TimeUnit.SECONDS);
        stringRedisTemplate.expire(RedisKeyConstant.authUserTokens(userId), ttl, TimeUnit.SECONDS);
        return user;
    }

    /**
     * 签发令牌：32 字节随机串 -> userId，同时登记到用户令牌集合
     */
    private LoginVO issueToken(SysUser user) {
        long ttl = properties.getAuth().getTokenTtlSeconds();
        String token = IdUtil.fastSimpleUUID() + IdUtil.fastSimpleUUID();
        String tokenKey = RedisKeyConstant.authToken(token);
        stringRedisTemplate.opsForValue().set(tokenKey, user.getUserId(), ttl, TimeUnit.SECONDS);
        String userTokensKey = RedisKeyConstant.authUserTokens(user.getUserId());
        stringRedisTemplate.opsForSet().add(userTokensKey, token);
        stringRedisTemplate.expire(userTokensKey, ttl, TimeUnit.SECONDS);

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setExpireSeconds(ttl);
        vo.setUser(toUserVO(user));
        return vo;
    }

    /**
     * 登录失败计数，达到上限锁定账号
     */
    private void handleLoginFail(SysUser user) {
        int failCount = (user.getFailCount() == null ? 0 : user.getFailCount()) + 1;
        int limit = properties.getAuth().getLoginFailLimit();
        if (failCount >= limit) {
            LocalDateTime lockUntil = LocalDateTime.now().plusMinutes(properties.getAuth().getLockMinutes());
            sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getUserId, user.getUserId())
                    .set(SysUser::getFailCount, 0)
                    .set(SysUser::getLockUntil, lockUntil));
            log.warn("账号连续登录失败已锁定 userId={} lockUntil={}", user.getUserId(), lockUntil);
            throw new BizException(BizErrorCodeEnum.ACCOUNT_LOCKED,
                    "密码错误次数过多，账号已锁定 " + properties.getAuth().getLockMinutes() + " 分钟");
        }
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getUserId, user.getUserId())
                .set(SysUser::getFailCount, failCount));
        user.setFailCount(failCount);
        throw new BizException(BizErrorCodeEnum.UNAUTHORIZED,
                "账号或密码错误，还可尝试 " + (limit - failCount) + " 次");
    }

    private void recordLoginSuccess(SysUser user) {
        LocalDateTime now = LocalDateTime.now();
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getUserId, user.getUserId())
                .set(SysUser::getFailCount, 0)
                .set(SysUser::getLockUntil, null)
                .set(SysUser::getLastLoginTime, now));
        user.setFailCount(0);
        user.setLockUntil(null);
        user.setLastLoginTime(now);
    }

    /**
     * 惰性清理已过期 VIP（定时任务兜底），避免过期用户继续占用套餐额度
     */
    private void refreshExpiredVip(SysUser user) {
        if (StrUtil.isBlank(user.getVipPlanCode()) || user.getVipExpireTime() == null) {
            return;
        }
        if (!user.getVipExpireTime().isBefore(LocalDateTime.now())) {
            return;
        }
        String expiredPlan = user.getVipPlanCode();
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getUserId, user.getUserId())
                .set(SysUser::getVipPlanCode, null)
                .set(SysUser::getVipExpireTime, null));
        user.setVipPlanCode(null);
        user.setVipExpireTime(null);
        log.info("用户 VIP 已到期降级 userId={} planCode={}", user.getUserId(), expiredPlan);
    }

    private UserVO toUserVO(SysUser user) {
        VipPlan plan = StrUtil.isBlank(user.getVipPlanCode())
                ? null : vipPlanMapper.selectById(user.getVipPlanCode());
        boolean vipActive = isVipActive(user);
        return UserVO.from(user, plan == null ? null : plan.getPlanName(), vipActive);
    }

    private boolean isVipActive(SysUser user) {
        return StrUtil.isNotBlank(user.getVipPlanCode())
                && user.getVipExpireTime() != null
                && user.getVipExpireTime().isAfter(LocalDateTime.now());
    }

    private SysUser requireCurrentUser() {
        String userId = TenantContextUtil.requireUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(BizErrorCodeEnum.UNAUTHORIZED, "用户不存在或已注销");
        }
        return user;
    }

    private void checkUsernameAvailable(String tenantId, String username) {
        SysUser exists = sysUserMapper.selectByTenantAndUsername(tenantId, username);
        if (exists != null) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "账号已存在");
        }
    }

    /**
     * 自助注册只允许白名单租户，避免通过注册进入其他租户读取数据
     */
    private void checkSelfRegisterAllowed(String tenantId) {
        List<String> allowed = properties.getAuth().getSelfRegisterTenants();
        if (allowed != null && !allowed.contains(tenantId)) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "该租户未开放自助注册，请联系管理员开通账号");
        }
    }

    /**
     * 使该用户全部令牌失效（改密、封号等场景）
     */
    private void revokeAllTokens(String userId) {
        String userTokensKey = RedisKeyConstant.authUserTokens(userId);
        Set<String> tokens = stringRedisTemplate.opsForSet().members(userTokensKey);
        if (tokens != null) {
            for (String token : tokens) {
                stringRedisTemplate.delete(RedisKeyConstant.authToken(token));
            }
        }
        stringRedisTemplate.delete(userTokensKey);
    }
}
