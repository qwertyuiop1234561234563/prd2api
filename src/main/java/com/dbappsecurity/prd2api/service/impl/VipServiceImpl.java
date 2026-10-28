package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.dto.RedeemCodeBatchDTO;
import com.dbappsecurity.prd2api.dto.RedeemCodeQueryDTO;
import com.dbappsecurity.prd2api.dto.UserQueryDTO;
import com.dbappsecurity.prd2api.dto.VipGrantDTO;
import com.dbappsecurity.prd2api.dto.VipPlanDTO;
import com.dbappsecurity.prd2api.entity.AuditLog;
import com.dbappsecurity.prd2api.entity.RedeemCode;
import com.dbappsecurity.prd2api.entity.SysUser;
import com.dbappsecurity.prd2api.entity.UserVip;
import com.dbappsecurity.prd2api.entity.VipPlan;
import com.dbappsecurity.prd2api.mapper.RedeemCodeMapper;
import com.dbappsecurity.prd2api.mapper.SysUserMapper;
import com.dbappsecurity.prd2api.mapper.UserVipMapper;
import com.dbappsecurity.prd2api.mapper.VipPlanMapper;
import com.dbappsecurity.prd2api.service.AuditLogService;
import com.dbappsecurity.prd2api.service.QuotaResolver;
import com.dbappsecurity.prd2api.service.VipService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.MyVipVO;
import com.dbappsecurity.prd2api.vo.QuotaInfoVO;
import com.dbappsecurity.prd2api.vo.RedeemCodeVO;
import com.dbappsecurity.prd2api.vo.UserVO;
import com.dbappsecurity.prd2api.vo.VipPlanVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * VIP 实现：套餐配置、兑换码原子抢占、开通记录、过期降级
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VipServiceImpl implements VipService {

    /** 兑换码字符集，去掉易混淆的 0/O/1/I */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 12;
    private static final int EXPIRE_BATCH_SIZE = 200;
    private static final DateTimeFormatter BATCH_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final VipPlanMapper vipPlanMapper;
    private final SysUserMapper sysUserMapper;
    private final UserVipMapper userVipMapper;
    private final RedeemCodeMapper redeemCodeMapper;
    private final QuotaResolver quotaResolver;
    private final AuditLogService auditLogService;

    @Override
    public List<VipPlanVO> listPlans() {
        return vipPlanMapper.selectList(new LambdaQueryWrapper<VipPlan>()
                        .eq(VipPlan::getStatus, 1)
                        .orderByAsc(VipPlan::getLevel))
                .stream().map(VipPlanVO::from).toList();
    }

    @Override
    public MyVipVO myVip() {
        SysUser user = requireCurrentUser();
        QuotaInfoVO quota = quotaResolver.currentQuota();
        MyVipVO vo = new MyVipVO();
        vo.setUserId(user.getUserId());
        vo.setUsername(user.getUsername());
        vo.setPlanCode(quota.getPlanCode());
        vo.setPlanName(quota.getPlanName());
        vo.setActive(QuotaInfoVO.SOURCE_VIP.equals(quota.getSource()));
        vo.setExpireTime(quota.getExpireTime());
        vo.setDailyUploadLimit(quota.getDailyUploadLimit());
        vo.setDailyTokenLimit(quota.getDailyTokenLimit());
        vo.setConcurrentTaskLimit(quota.getConcurrentTaskLimit());
        vo.setMaxFileSizeMb(quota.getMaxFileSizeMb());
        vo.setUsedUploadCount(quota.getUsedUploadCount());
        vo.setUsedTokenCount(quota.getUsedTokenCount());
        if (vo.getActive()) {
            VipPlan plan = vipPlanMapper.selectById(quota.getPlanCode());
            vo.setLevel(plan == null ? null : plan.getLevel());
            vo.setModelTier(plan == null ? null : plan.getModelTier());
            vo.setRemainDays(Math.max(1,
                    ChronoUnit.DAYS.between(LocalDateTime.now(), quota.getExpireTime())));
        } else {
            vo.setRemainDays(0L);
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MyVipVO redeem(String code) {
        SysUser user = requireCurrentUser();
        String trimmed = StrUtil.trim(code);
        if (StrUtil.isBlank(trimmed)) {
            throw new BizException(BizErrorCodeEnum.REDEEM_CODE_INVALID, "兑换码不能为空");
        }
        RedeemCode redeemCode = redeemCodeMapper.selectOne(new LambdaQueryWrapper<RedeemCode>()
                .eq(RedeemCode::getCode, trimmed).last("limit 1"));
        if (redeemCode == null) {
            throw new BizException(BizErrorCodeEnum.REDEEM_CODE_INVALID, "兑换码不存在");
        }
        if (!RedeemCode.STATUS_UNUSED.equals(redeemCode.getStatus())) {
            throw new BizException(BizErrorCodeEnum.REDEEM_CODE_INVALID, "兑换码已被使用或已失效");
        }
        if (redeemCode.getExpireTime() != null && redeemCode.getExpireTime().isBefore(LocalDateTime.now())) {
            redeemCodeMapper.markExpired(LocalDateTime.now());
            throw new BizException(BizErrorCodeEnum.REDEEM_CODE_INVALID, "兑换码已过期");
        }
        VipPlan plan = requireGrantablePlan(redeemCode.getPlanCode());

        // 原子抢占，避免并发重复兑换
        int rows = redeemCodeMapper.claim(trimmed, user.getUserId(), LocalDateTime.now());
        if (rows == 0) {
            throw new BizException(BizErrorCodeEnum.REDEEM_CODE_INVALID, "兑换码已被使用");
        }
        applyVip(user, plan, plan.getDurationDays(), UserVip.SOURCE_REDEEM, user.getUserId(), "兑换码 " + trimmed);
        log.info("兑换码兑换成功 userId={} plan={} code={}", user.getUserId(), plan.getPlanCode(), trimmed);
        return myVip();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MyVipVO activateCouponVip(String planCode, int durationDays, String remark) {
        SysUser user = requireCurrentUser();
        VipPlan plan = requireGrantablePlan(planCode);
        int days = durationDays > 0 ? durationDays : plan.getDurationDays();
        applyVip(user, plan, days, UserVip.SOURCE_COUPON, user.getUserId(), remark);
        log.info("优惠券开通 VIP userId={} plan={} days={}", user.getUserId(), plan.getPlanCode(), days);
        return myVip();
    }

    @Override
    public Page<UserVO> pageUsers(UserQueryDTO dto) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StrUtil.isNotBlank(dto.getTenantId()), SysUser::getTenantId, dto.getTenantId())
                .like(StrUtil.isNotBlank(dto.getUsername()), SysUser::getUsername, dto.getUsername())
                .eq(StrUtil.isNotBlank(dto.getPlanCode()), SysUser::getVipPlanCode, dto.getPlanCode())
                .orderByDesc(SysUser::getCreateTime);
        if (dto.getVipActive() != null && dto.getVipActive() == 1) {
            wrapper.isNotNull(SysUser::getVipPlanCode)
                    .gt(SysUser::getVipExpireTime, LocalDateTime.now());
        } else if (dto.getVipActive() != null) {
            wrapper.and(w -> w.isNull(SysUser::getVipPlanCode)
                    .or().isNull(SysUser::getVipExpireTime)
                    .or().le(SysUser::getVipExpireTime, LocalDateTime.now()));
        }
        Page<SysUser> page = sysUserMapper.selectPage(new Page<>(dto.getPageNum(), dto.getPageSize()), wrapper);
        Map<String, VipPlan> plans = loadPlans();
        Page<UserVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream()
                .map(user -> toUserVO(user, plans.get(user.getVipPlanCode())))
                .toList());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO grant(VipGrantDTO dto) {
        SysUser user = sysUserMapper.selectById(dto.getUserId());
        if (user == null) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "用户不存在");
        }
        VipPlan plan = requireGrantablePlan(dto.getPlanCode());
        applyVip(user, plan, plan.getDurationDays(), UserVip.SOURCE_ADMIN, TenantContextUtil.getUserKey(), dto.getRemark());
        log.info("管理员开通 VIP userId={} plan={} operator={}",
                user.getUserId(), plan.getPlanCode(), TenantContextUtil.getUserKey());
        return toUserVO(sysUserMapper.selectById(user.getUserId()), plan);
    }

    @Override
    public List<VipPlanVO> listAllPlans() {
        return vipPlanMapper.selectList(new LambdaQueryWrapper<VipPlan>()
                        .orderByAsc(VipPlan::getLevel))
                .stream().map(VipPlanVO::from).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VipPlanVO savePlan(VipPlanDTO dto) {
        VipPlan existing = vipPlanMapper.selectById(dto.getPlanCode());
        VipPlan plan = existing == null ? new VipPlan() : existing;
        plan.setPlanCode(dto.getPlanCode());
        plan.setPlanName(dto.getPlanName());
        plan.setLevel(dto.getLevel());
        plan.setDurationDays(dto.getDurationDays());
        plan.setDailyUploadLimit(dto.getDailyUploadLimit());
        plan.setDailyTokenLimit(dto.getDailyTokenLimit());
        plan.setConcurrentTaskLimit(dto.getConcurrentTaskLimit());
        plan.setMaxFileSizeMb(dto.getMaxFileSizeMb());
        plan.setModelTier(StrUtil.blankToDefault(dto.getModelTier(), "basic"));
        plan.setPriceCent(dto.getPriceCent() == null ? 0 : dto.getPriceCent());
        plan.setDescription(dto.getDescription());
        plan.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        if (existing == null) {
            vipPlanMapper.insert(plan);
        } else {
            vipPlanMapper.updateById(plan);
        }
        return VipPlanVO.from(vipPlanMapper.selectById(plan.getPlanCode()));
    }

    @Override
    public void offlinePlan(String planCode) {
        VipPlan plan = vipPlanMapper.selectById(planCode);
        if (plan == null) {
            throw new BizException(BizErrorCodeEnum.VIP_PLAN_INVALID, "套餐不存在");
        }
        if ("FREE".equals(planCode)) {
            throw new BizException(BizErrorCodeEnum.VIP_PLAN_INVALID, "免费套餐不允许下架");
        }
        vipPlanMapper.update(null, new LambdaUpdateWrapper<VipPlan>()
                .eq(VipPlan::getPlanCode, planCode)
                .set(VipPlan::getStatus, 0));
        log.info("套餐已下架 planCode={} operator={}", planCode, TenantContextUtil.getUserKey());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<RedeemCodeVO> generateCodes(RedeemCodeBatchDTO dto) {
        VipPlan plan = requireGrantablePlan(dto.getPlanCode());
        int count = dto.getCount() == null ? 0 : dto.getCount();
        if (count <= 0) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "生成数量必须大于 0");
        }
        String batchNo = "B" + LocalDateTime.now().format(BATCH_FORMAT)
                + RandomUtil.randomNumbers(3);
        LocalDateTime expireTime = (dto.getExpireDays() == null || dto.getExpireDays() <= 0)
                ? null : LocalDateTime.now().plusDays(dto.getExpireDays());

        List<RedeemCodeVO> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            RedeemCode redeemCode = new RedeemCode();
            redeemCode.setCode(RandomUtil.randomString(CODE_ALPHABET, CODE_LENGTH));
            redeemCode.setPlanCode(plan.getPlanCode());
            redeemCode.setStatus(RedeemCode.STATUS_UNUSED);
            redeemCode.setBatchNo(batchNo);
            redeemCode.setExpireTime(expireTime);
            redeemCodeMapper.insert(redeemCode);
            result.add(toCodeVO(redeemCode, plan.getPlanName()));
        }
        log.info("批量生成兑换码 plan={} count={} batchNo={} operator={}",
                plan.getPlanCode(), count, batchNo, TenantContextUtil.getUserKey());
        return result;
    }

    @Override
    public Page<RedeemCodeVO> pageCodes(RedeemCodeQueryDTO dto) {
        LambdaQueryWrapper<RedeemCode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StrUtil.isNotBlank(dto.getPlanCode()), RedeemCode::getPlanCode, dto.getPlanCode())
                .eq(StrUtil.isNotBlank(dto.getStatus()), RedeemCode::getStatus, dto.getStatus())
                .eq(StrUtil.isNotBlank(dto.getBatchNo()), RedeemCode::getBatchNo, dto.getBatchNo())
                .orderByDesc(RedeemCode::getId);
        Page<RedeemCode> page = redeemCodeMapper.selectPage(new Page<>(dto.getPageNum(), dto.getPageSize()), wrapper);
        Map<String, VipPlan> plans = loadPlans();
        Page<RedeemCodeVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream()
                .map(code -> toCodeVO(code, planName(plans, code.getPlanCode())))
                .toList());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int expireOverdue() {
        LocalDateTime now = LocalDateTime.now();
        List<SysUser> expired = sysUserMapper.selectExpiredVip(now, EXPIRE_BATCH_SIZE);
        for (SysUser user : expired) {
            sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getUserId, user.getUserId())
                    .set(SysUser::getVipPlanCode, null)
                    .set(SysUser::getVipExpireTime, null));
            auditLogService.record(buildExpireAudit(user));
            log.info("VIP 到期降级 userId={} tenantId={} plan={}",
                    user.getUserId(), user.getTenantId(), user.getVipPlanCode());
        }
        int codes = redeemCodeMapper.markExpired(now);
        if (!expired.isEmpty() || codes > 0) {
            log.info("VIP 过期处理完成 users={} codes={}", expired.size(), codes);
        }
        return expired.size();
    }

    /**
     * 开通 VIP：同套餐续期从原到期时间累加，切套餐则从当前时间重新计算
     */
    private void applyVip(SysUser user, VipPlan plan, int durationDays,
                          String source, String operator, String remark) {
        LocalDateTime now = LocalDateTime.now();
        // 数据库原子续期：同套餐未到期时在原到期时间上累加，避免并发开通时「先读后写」丢失时长
        int rows = sysUserMapper.grantVip(user.getUserId(), plan.getPlanCode(), durationDays);
        if (rows == 0) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "用户不存在或已注销");
        }
        SysUser latest = sysUserMapper.selectById(user.getUserId());
        LocalDateTime expireTime = latest == null ? now.plusDays(durationDays) : latest.getVipExpireTime();

        UserVip record = new UserVip();
        record.setUserId(user.getUserId());
        record.setPlanCode(plan.getPlanCode());
        record.setSource(source);
        record.setStartTime(now);
        record.setExpireTime(expireTime);
        record.setOperator(StrUtil.maxLength(StrUtil.blankToDefault(operator, "system"), 64));
        record.setRemark(StrUtil.maxLength(remark, 256));
        userVipMapper.insert(record);
    }

    private VipPlan requireGrantablePlan(String planCode) {
        VipPlan plan = vipPlanMapper.selectById(planCode);
        if (plan == null || plan.getStatus() == null || plan.getStatus() != 1) {
            throw new BizException(BizErrorCodeEnum.VIP_PLAN_INVALID, "套餐不存在或已下架");
        }
        if (!plan.grantable()) {
            throw new BizException(BizErrorCodeEnum.VIP_PLAN_INVALID, "该套餐不支持开通");
        }
        return plan;
    }

    private SysUser requireCurrentUser() {
        String userId = TenantContextUtil.requireUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(BizErrorCodeEnum.UNAUTHORIZED, "用户不存在或已注销");
        }
        return user;
    }

    private Map<String, VipPlan> loadPlans() {
        Map<String, VipPlan> plans = new HashMap<>();
        for (VipPlan plan : vipPlanMapper.selectList(null)) {
            plans.put(plan.getPlanCode(), plan);
        }
        return plans;
    }

    private String planName(Map<String, VipPlan> plans, String planCode) {
        VipPlan plan = plans.get(planCode);
        return plan == null ? null : plan.getPlanName();
    }

    private UserVO toUserVO(SysUser user, VipPlan plan) {
        boolean active = plan != null && user.getVipExpireTime() != null
                && user.getVipExpireTime().isAfter(LocalDateTime.now());
        return UserVO.from(user, plan == null ? null : plan.getPlanName(), active);
    }

    private RedeemCodeVO toCodeVO(RedeemCode code, String planName) {
        RedeemCodeVO vo = new RedeemCodeVO();
        vo.setId(code.getId());
        vo.setCode(code.getCode());
        vo.setPlanCode(code.getPlanCode());
        vo.setPlanName(planName);
        vo.setStatus(code.getStatus());
        vo.setBatchNo(code.getBatchNo());
        vo.setUsedByUser(code.getUsedByUser());
        vo.setUsedTime(code.getUsedTime());
        vo.setExpireTime(code.getExpireTime());
        vo.setCreateTime(code.getCreateTime());
        return vo;
    }

    private AuditLog buildExpireAudit(SysUser user) {
        AuditLog auditLog = new AuditLog();
        auditLog.setTenantId(user.getTenantId());
        auditLog.setUserKey(user.getUsername());
        auditLog.setUserRole(TenantContextUtil.USER_ROLE);
        auditLog.setAction("VIP 到期降级");
        auditLog.setTarget(user.getUserId());
        auditLog.setSuccess(1);
        auditLog.setCostMs(0L);
        auditLog.setErrorMsg(null);
        return auditLog;
    }
}
