package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.dto.CouponClaimQueryDTO;
import com.dbappsecurity.prd2api.dto.CouponCreateDTO;
import com.dbappsecurity.prd2api.dto.CouponQueryDTO;
import com.dbappsecurity.prd2api.entity.Coupon;
import com.dbappsecurity.prd2api.entity.UserCoupon;
import com.dbappsecurity.prd2api.entity.VipPlan;
import com.dbappsecurity.prd2api.mapper.CouponMapper;
import com.dbappsecurity.prd2api.mapper.UserCouponMapper;
import com.dbappsecurity.prd2api.mapper.VipPlanMapper;
import com.dbappsecurity.prd2api.service.CouponService;
import com.dbappsecurity.prd2api.service.VipService;
import com.dbappsecurity.prd2api.util.CouponClaimUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.CouponVO;
import com.dbappsecurity.prd2api.vo.MyVipVO;
import com.dbappsecurity.prd2api.vo.UserCouponVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 优惠券实现：Redis Lua 原子领取 + 数据库唯一键兜底 + 核销开通 VIP
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private static final int CLAIM_PAGE_MAX = 200;

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final VipPlanMapper vipPlanMapper;
    private final CouponClaimUtil couponClaimUtil;
    private final VipService vipService;

    @Override
    public List<CouponVO> listAvailable() {
        LocalDateTime now = LocalDateTime.now();
        String userId = TenantContextUtil.requireUserId();
        List<Coupon> coupons = couponMapper.selectList(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getStatus, Coupon.STATUS_ACTIVE)
                .and(w -> w.isNull(Coupon::getExpireTime).or().gt(Coupon::getExpireTime, now))
                .orderByDesc(Coupon::getCouponId)
                .last("limit " + CLAIM_PAGE_MAX));
        if (coupons.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, VipPlan> plans = loadPlans();
        Map<Long, Integer> mine = countMine(userId, coupons.stream().map(Coupon::getCouponId).toList());
        List<CouponVO> result = new ArrayList<>(coupons.size());
        for (Coupon coupon : coupons) {
            result.add(CouponVO.from(coupon, plans.get(coupon.getPlanCode()), mine.get(coupon.getCouponId())));
        }
        return result;
    }

    @Override
    public UserCouponVO claim(Long couponId) {
        String userId = TenantContextUtil.requireUserId();
        String tenantId = TenantContextUtil.requireTenantId();
        Coupon coupon = couponMapper.selectById(couponId);
        LocalDateTime now = LocalDateTime.now();
        if (coupon == null || !coupon.claimable(now)) {
            throw new BizException(BizErrorCodeEnum.COUPON_INVALID, "优惠券不存在或已停止领取");
        }

        long ttl = ttlSeconds(coupon.getExpireTime(), now);
        int remainStock = Math.max(0,
                nvl(coupon.getTotalStock()) - nvl(coupon.getClaimedCount()));
        couponClaimUtil.initStock(couponId, remainStock, ttl);
        int perUserLimit = Math.max(1, nvl(coupon.getPerUserLimit()));

        CouponClaimUtil.ClaimResult claim = couponClaimUtil.claim(couponId, userId, perUserLimit, ttl);
        if (claim.status() == CouponClaimUtil.ClaimResult.Status.NOT_INITIALIZED) {
            // 库存缓存被清理（如下架后重新上架），重新按数据库剩余量初始化后重试一次
            couponClaimUtil.initStock(couponId, remainStock, ttl);
            claim = couponClaimUtil.claim(couponId, userId, perUserLimit, ttl);
        }
        switch (claim.status()) {
            case SUCCESS -> {
            }
            case SOLD_OUT -> throw new BizException(BizErrorCodeEnum.COUPON_SOLD_OUT, "优惠券已领完");
            case LIMIT_REACHED -> throw new BizException(BizErrorCodeEnum.COUPON_LIMIT_REACHED,
                    "每张优惠券每人限领 " + perUserLimit + " 张");
            default -> throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "优惠券领取繁忙，请稍后重试");
        }

        // Redis 扣减成功后落库；数据库条件自增与唯一键共同兜底，失败时补偿 Redis
        boolean increased = false;
        try {
            if (couponMapper.increaseClaimed(couponId) == 0) {
                throw new BizException(BizErrorCodeEnum.COUPON_SOLD_OUT, "优惠券已领完");
            }
            increased = true;
            VipPlan plan = vipPlanMapper.selectById(coupon.getPlanCode());
            if (plan == null || plan.getStatus() == null || plan.getStatus() != 1 || !plan.grantable()) {
                throw new BizException(BizErrorCodeEnum.COUPON_INVALID, "优惠券关联套餐已下架");
            }
            UserCoupon userCoupon = new UserCoupon();
            userCoupon.setCouponId(couponId);
            userCoupon.setUserId(userId);
            userCoupon.setTenantId(tenantId);
            userCoupon.setClaimNo(claim.claimNo());
            userCoupon.setStatus(UserCoupon.STATUS_UNUSED);
            userCoupon.setReceiveTime(now);
            userCoupon.setExpireTime(resolveUseExpireTime(coupon, now));
            userCouponMapper.insert(userCoupon);
            log.info("优惠券领取成功 couponId={} userId={} claimNo={}",
                    couponId, userId, claim.claimNo());
            return toUserCouponVO(userCoupon, coupon, plan);
        } catch (Exception e) {
            couponClaimUtil.rollback(couponId, userId, ttl);
            if (increased) {
                couponMapper.decreaseClaimed(couponId);
            }
            if (e instanceof BizException bizException) {
                throw bizException;
            }
            log.error("优惠券落库失败已回滚 couponId={} userId={}", couponId, userId, e);
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "优惠券领取失败，请稍后重试");
        }
    }

    @Override
    public List<UserCouponVO> myCoupons() {
        String userId = TenantContextUtil.requireUserId();
        List<UserCoupon> mine = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getId)
                .last("limit " + CLAIM_PAGE_MAX));
        if (mine.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Coupon> coupons = new HashMap<>();
        for (Coupon coupon : couponMapper.selectBatchIds(
                mine.stream().map(UserCoupon::getCouponId).distinct().toList())) {
            coupons.put(coupon.getCouponId(), coupon);
        }
        Map<String, VipPlan> plans = loadPlans();
        List<UserCouponVO> result = new ArrayList<>(mine.size());
        for (UserCoupon userCoupon : mine) {
            Coupon coupon = coupons.get(userCoupon.getCouponId());
            result.add(toUserCouponVO(userCoupon, coupon,
                    coupon == null ? null : plans.get(coupon.getPlanCode())));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MyVipVO use(Long userCouponId) {
        String userId = TenantContextUtil.requireUserId();
        UserCoupon userCoupon = userCouponMapper.selectById(userCouponId);
        if (userCoupon == null || !userId.equals(userCoupon.getUserId())) {
            throw new BizException(BizErrorCodeEnum.NOT_FOUND, "优惠券不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!UserCoupon.STATUS_UNUSED.equals(userCoupon.getStatus())) {
            throw new BizException(BizErrorCodeEnum.COUPON_ALREADY_USED, "优惠券已使用或已失效");
        }
        if (userCoupon.getExpireTime() != null && !userCoupon.getExpireTime().isAfter(now)) {
            userCouponMapper.markExpired(now);
            throw new BizException(BizErrorCodeEnum.COUPON_ALREADY_USED, "优惠券已过期");
        }
        Coupon coupon = couponMapper.selectById(userCoupon.getCouponId());
        if (coupon == null) {
            throw new BizException(BizErrorCodeEnum.COUPON_INVALID, "优惠券不存在");
        }
        VipPlan plan = vipPlanMapper.selectById(coupon.getPlanCode());
        // 先校验套餐可开通，避免券已核销却开不出 VIP
        if (plan == null || plan.getStatus() == null || plan.getStatus() != 1 || !plan.grantable()) {
            throw new BizException(BizErrorCodeEnum.VIP_PLAN_INVALID, "优惠券关联套餐已下架，请联系管理员");
        }
        int days = coupon.getDurationDays() != null && coupon.getDurationDays() > 0
                ? coupon.getDurationDays() : (plan.getDurationDays() == null ? 0 : plan.getDurationDays());
        if (days <= 0) {
            throw new BizException(BizErrorCodeEnum.VIP_PLAN_INVALID, "优惠券套餐有效天数配置异常");
        }
        // 原子核销，避免并发重复使用同一张券
        if (userCouponMapper.markUsed(userCouponId, userId, now) == 0) {
            throw new BizException(BizErrorCodeEnum.COUPON_ALREADY_USED, "优惠券已使用或已失效");
        }
        String remark = StrUtil.maxLength("优惠券 " + coupon.getCouponName(), 256);
        MyVipVO vo = vipService.activateCouponVip(coupon.getPlanCode(), days, remark);
        log.info("优惠券核销成功 userCouponId={} userId={} plan={}", userCouponId, userId, coupon.getPlanCode());
        return vo;
    }

    @Override
    public CouponVO create(CouponCreateDTO dto) {
        VipPlan plan = vipPlanMapper.selectById(dto.getPlanCode());
        if (plan == null || plan.getStatus() == null || plan.getStatus() != 1 || !plan.grantable()) {
            throw new BizException(BizErrorCodeEnum.VIP_PLAN_INVALID, "套餐不存在或已下架");
        }
        LocalDateTime now = LocalDateTime.now();
        int totalStock = dto.getTotalStock() == null ? 0 : dto.getTotalStock();
        int perUserLimit = dto.getPerUserLimit() == null ? 1 : dto.getPerUserLimit();
        if (totalStock < 1) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "发放总量至少为 1");
        }
        if (perUserLimit < 1 || perUserLimit > totalStock) {
            throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "每人限领数量不能大于发放总量");
        }
        int expireDays = dto.getExpireDays() == null ? 0 : dto.getExpireDays();
        Coupon coupon = new Coupon();
        coupon.setCouponName(dto.getCouponName());
        coupon.setPlanCode(plan.getPlanCode());
        coupon.setDurationDays(dto.getDurationDays() == null ? 0 : dto.getDurationDays());
        coupon.setTotalStock(totalStock);
        coupon.setPerUserLimit(perUserLimit);
        coupon.setClaimedCount(0);
        coupon.setValidDays(dto.getValidDays() == null ? 0 : dto.getValidDays());
        coupon.setStartTime(now);
        coupon.setExpireTime(expireDays > 0 ? now.plusDays(expireDays) : null);
        coupon.setStatus(Coupon.STATUS_ACTIVE);
        coupon.setCreateBy(StrUtil.maxLength(
                StrUtil.blankToDefault(TenantContextUtil.getUserKey(), "system"), 64));
        coupon.setCreateTime(now);
        couponMapper.insert(coupon);
        couponClaimUtil.initStock(coupon.getCouponId(), coupon.getTotalStock(),
                ttlSeconds(coupon.getExpireTime(), now));
        log.info("优惠券发放成功 couponId={} plan={} stock={} operator={}",
                coupon.getCouponId(), plan.getPlanCode(), coupon.getTotalStock(), coupon.getCreateBy());
        return CouponVO.from(coupon, plan, 0);
    }

    @Override
    public Page<CouponVO> pageCoupons(CouponQueryDTO dto) {
        LambdaQueryWrapper<Coupon> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(dto.getStatus() != null, Coupon::getStatus, dto.getStatus())
                .eq(StrUtil.isNotBlank(dto.getPlanCode()), Coupon::getPlanCode, dto.getPlanCode())
                .like(StrUtil.isNotBlank(dto.getCouponName()), Coupon::getCouponName, dto.getCouponName())
                .orderByDesc(Coupon::getCouponId);
        Page<Coupon> page = couponMapper.selectPage(new Page<>(dto.getPageNum(), dto.getPageSize()), wrapper);
        Map<String, VipPlan> plans = loadPlans();
        Page<CouponVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream()
                .map(coupon -> CouponVO.from(coupon, plans.get(coupon.getPlanCode()), null))
                .toList());
        return result;
    }

    @Override
    public void offline(Long couponId) {
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            throw new BizException(BizErrorCodeEnum.COUPON_INVALID, "优惠券不存在");
        }
        couponMapper.update(null, new LambdaUpdateWrapper<Coupon>()
                .eq(Coupon::getCouponId, couponId)
                .set(Coupon::getStatus, Coupon.STATUS_OFFLINE));
        couponClaimUtil.clearStock(couponId);
        log.info("优惠券已下架 couponId={} operator={}", couponId, TenantContextUtil.getUserKey());
    }

    @Override
    public Page<UserCouponVO> pageClaims(Long couponId, CouponClaimQueryDTO dto) {
        if (couponMapper.selectById(couponId) == null) {
            throw new BizException(BizErrorCodeEnum.COUPON_INVALID, "优惠券不存在");
        }
        return (Page<UserCouponVO>) userCouponMapper.selectClaimPage(
                new Page<>(dto.getPageNum(), dto.getPageSize()), couponId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int expireOverdue() {
        LocalDateTime now = LocalDateTime.now();
        int coupons = couponMapper.markExpired(now);
        int userCoupons = userCouponMapper.markExpired(now);
        if (coupons > 0 || userCoupons > 0) {
            log.info("优惠券过期处理完成 batches={} userCoupons={}", coupons, userCoupons);
        }
        return userCoupons;
    }

    private Map<String, VipPlan> loadPlans() {
        Map<String, VipPlan> plans = new HashMap<>();
        for (VipPlan plan : vipPlanMapper.selectList(null)) {
            plans.put(plan.getPlanCode(), plan);
        }
        return plans;
    }

    private Map<Long, Integer> countMine(String userId, List<Long> couponIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (couponIds.isEmpty()) {
            return counts;
        }
        List<UserCoupon> mine = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .select(UserCoupon::getCouponId)
                .eq(UserCoupon::getUserId, userId)
                .in(UserCoupon::getCouponId, couponIds));
        for (UserCoupon userCoupon : mine) {
            counts.merge(userCoupon.getCouponId(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 使用截止时间：优先取领取后有效天数，否则跟随领取截止时间
     */
    private LocalDateTime resolveUseExpireTime(Coupon coupon, LocalDateTime now) {
        if (coupon.getValidDays() != null && coupon.getValidDays() > 0) {
            return now.plusDays(coupon.getValidDays());
        }
        return coupon.getExpireTime();
    }

    /**
     * 库存 Key 过期时间：领取截止后 1 天回收，长期有效返回 0
     */
    private long ttlSeconds(LocalDateTime expireTime, LocalDateTime now) {
        if (expireTime == null) {
            return 0L;
        }
        return Math.max(1L, Duration.between(now, expireTime).getSeconds() + 86400L);
    }

    private int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private UserCouponVO toUserCouponVO(UserCoupon userCoupon, Coupon coupon, VipPlan plan) {
        UserCouponVO vo = new UserCouponVO();
        vo.setId(userCoupon.getId());
        vo.setCouponId(userCoupon.getCouponId());
        vo.setCouponName(coupon == null ? null : coupon.getCouponName());
        vo.setPlanCode(coupon == null ? null : coupon.getPlanCode());
        vo.setPlanName(plan == null ? null : plan.getPlanName());
        if (coupon != null) {
            int days = coupon.getDurationDays() != null && coupon.getDurationDays() > 0
                    ? coupon.getDurationDays()
                    : (plan == null || plan.getDurationDays() == null ? 0 : plan.getDurationDays());
            vo.setDurationDays(days);
        }
        vo.setUserId(userCoupon.getUserId());
        vo.setClaimNo(userCoupon.getClaimNo());
        vo.setStatus(userCoupon.getStatus());
        vo.setReceiveTime(userCoupon.getReceiveTime());
        vo.setUseTime(userCoupon.getUseTime());
        vo.setExpireTime(userCoupon.getExpireTime());
        return vo;
    }
}
