package com.dbappsecurity.prd2api.config;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dbappsecurity.prd2api.entity.SysUser;
import com.dbappsecurity.prd2api.entity.UserVip;
import com.dbappsecurity.prd2api.entity.VipPlan;
import com.dbappsecurity.prd2api.mapper.SysUserMapper;
import com.dbappsecurity.prd2api.mapper.UserVipMapper;
import com.dbappsecurity.prd2api.mapper.VipPlanMapper;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 内置管理员初始化
 * <p>
 * 首次启动按配置创建管理员账号并开通最高等级 VIP；账号已存在时只补齐 ADMIN 角色与最高套餐，
 * 不覆盖已修改过的密码，避免重启把线上密码重置回配置值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private static final String INIT_OPERATOR = "system-init";
    private static final String DEFAULT_DISPLAY_NAME = "系统管理员";

    private final SysUserMapper sysUserMapper;
    private final VipPlanMapper vipPlanMapper;
    private final UserVipMapper userVipMapper;
    private final Prd2ApiProperties properties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        Prd2ApiProperties.AdminInit config = properties.getAdminInit();
        if (config == null || !config.isEnabled()) {
            return;
        }
        if (StrUtil.isBlank(config.getPassword())) {
            log.info("内置管理员初始化已跳过：未配置 prd2api.admin-init.password");
            return;
        }
        VipPlan topPlan = resolveTopPlan(config.getPlanCode());
        if (topPlan == null) {
            log.warn("内置管理员初始化未找到可开通的 VIP 套餐，仅创建账号，请先执行 schema.sql 种子数据");
        } else if (topPlan.getStatus() == null || topPlan.getStatus() != 1) {
            // 最高套餐被下架时不静默降级，重新上架后授予内置管理员，保证最高权益可用
            log.warn("最高套餐 {} 处于下架状态，内置管理员初始化将重新上架", topPlan.getPlanCode());
            vipPlanMapper.update(null, new LambdaUpdateWrapper<VipPlan>()
                    .eq(VipPlan::getPlanCode, topPlan.getPlanCode())
                    .set(VipPlan::getStatus, 1));
            topPlan.setStatus(1);
        }

        SysUser user = sysUserMapper.selectByTenantAndUsername(config.getTenantId(), config.getUsername());
        if (user == null) {
            user = createAdmin(config);
        } else {
            ensureAdminRole(user);
            if (!BCrypt.checkpw(config.getPassword(), user.getPasswordHash())) {
                log.warn("内置管理员 {} 已存在且密码与配置不一致，为避免覆盖线上密码不做重置，"
                        + "如需重置请使用 /api/auth/password 或后台操作", user.getUsername());
            }
        }
        if (user == null) {
            log.warn("内置管理员初始化失败：账号未创建成功，请检查数据库唯一键与连接");
            return;
        }
        if (topPlan != null) {
            grantTopVipIfNeeded(user, topPlan, config.getVipDays());
        }
    }

    private SysUser createAdmin(Prd2ApiProperties.AdminInit config) {
        SysUser user = new SysUser();
        user.setUserId(IdUtil.fastSimpleUUID());
        user.setTenantId(config.getTenantId());
        user.setUsername(config.getUsername());
        user.setPasswordHash(BCrypt.hashpw(config.getPassword()));
        user.setDisplayName(DEFAULT_DISPLAY_NAME);
        user.setRole(TenantContextUtil.ADMIN_ROLE);
        user.setStatus(SysUser.STATUS_ACTIVE);
        user.setFailCount(0);
        user.setDeleted(0);
        try {
            sysUserMapper.insert(user);
            log.info("内置管理员创建成功 userId={} tenantId={} username={}",
                    user.getUserId(), user.getTenantId(), user.getUsername());
            return user;
        } catch (DuplicateKeyException e) {
            // 多实例同时启动，另一实例已创建，回查后继续补齐角色与 VIP
            log.info("内置管理员已被其他实例创建 username={}，转为补齐初始化", config.getUsername());
            return sysUserMapper.selectByTenantAndUsername(config.getTenantId(), config.getUsername());
        }
    }

    private void ensureAdminRole(SysUser user) {
        if (TenantContextUtil.ADMIN_ROLE.equalsIgnoreCase(user.getRole())) {
            return;
        }
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getUserId, user.getUserId())
                .set(SysUser::getRole, TenantContextUtil.ADMIN_ROLE));
        user.setRole(TenantContextUtil.ADMIN_ROLE);
        log.info("内置管理员角色已纠正 userId={} username={}", user.getUserId(), user.getUsername());
    }

    /**
     * 已持有最高套餐且未过期则跳过，避免每次重启重复写开通记录
     */
    private void grantTopVipIfNeeded(SysUser user, VipPlan topPlan, int vipDays) {
        LocalDateTime now = LocalDateTime.now();
        boolean samePlan = topPlan.getPlanCode().equals(user.getVipPlanCode());
        boolean active = user.getVipExpireTime() != null && user.getVipExpireTime().isAfter(now);
        if (samePlan && active) {
            return;
        }
        int days = vipDays > 0 ? vipDays : topPlan.getDurationDays();
        LocalDateTime expireTime = now.plusDays(days);
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getUserId, user.getUserId())
                .set(SysUser::getVipPlanCode, topPlan.getPlanCode())
                .set(SysUser::getVipExpireTime, expireTime));

        UserVip record = new UserVip();
        record.setUserId(user.getUserId());
        record.setPlanCode(topPlan.getPlanCode());
        record.setSource(UserVip.SOURCE_ADMIN);
        record.setStartTime(now);
        record.setExpireTime(expireTime);
        record.setOperator(INIT_OPERATOR);
        record.setRemark("内置管理员初始化");
        userVipMapper.insert(record);
        log.info("内置管理员已开通最高套餐 userId={} plan={} expireTime={}",
                user.getUserId(), topPlan.getPlanCode(), expireTime);
    }

    /**
     * 取等级最高的套餐（含已下架，由调用方决定是否重新上架）；配置了 planCode 时以其为准
     */
    private VipPlan resolveTopPlan(String planCode) {
        if (StrUtil.isNotBlank(planCode)) {
            VipPlan plan = vipPlanMapper.selectById(planCode);
            if (plan == null || !plan.grantable()) {
                log.warn("配置的套餐不可开通 planCode={}，请检查 prd2api.admin-init.plan-code", planCode);
                return null;
            }
            return plan;
        }
        List<VipPlan> plans = vipPlanMapper.selectList(new LambdaQueryWrapper<VipPlan>());
        return plans.stream()
                .filter(VipPlan::grantable)
                .max(Comparator.comparing(VipPlan::getLevel))
                .orElse(null);
    }
}
