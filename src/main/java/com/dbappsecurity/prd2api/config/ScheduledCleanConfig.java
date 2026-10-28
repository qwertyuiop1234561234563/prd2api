package com.dbappsecurity.prd2api.config;

import com.dbappsecurity.prd2api.service.CouponService;
import com.dbappsecurity.prd2api.service.FileService;
import com.dbappsecurity.prd2api.service.VipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时清理：过期文件、过期报告缓存、过期导出临时文件、VIP 过期降级
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ScheduledCleanConfig {

    private final FileService fileService;
    private final VipService vipService;
    private final CouponService couponService;
    private final Prd2ApiProperties properties;

    @Scheduled(cron = "${prd2api.clean.cron:0 30 3 * * ?}")
    public void clean() {
        if (!properties.getClean().isEnable()) {
            return;
        }
        log.info("定时清理开始 expireDay={}", properties.getClean().getExpireDay());
        try {
            fileService.cleanExpired();
        } catch (Exception e) {
            log.error("清理过期文件失败", e);
        }
        try {
            fileService.cleanExpiredCache();
        } catch (Exception e) {
            log.error("清理过期报告缓存失败", e);
        }
        try {
            fileService.cleanExpiredExports();
        } catch (Exception e) {
            log.error("清理过期导出文件失败", e);
        }
        log.info("定时清理结束");
    }

    /**
     * 每日凌晨 4 点：清理已过期 VIP 标记、过期兑换码与过期优惠券（请求时已按时间惰性判定，此处只做数据收敛）
     */
    @Scheduled(cron = "${prd2api.vip.expire-cron:0 0 4 * * ?}")
    public void expireVip() {
        try {
            int users = vipService.expireOverdue();
            log.info("VIP 过期降级完成 users={}", users);
        } catch (Exception e) {
            log.error("VIP 过期降级失败", e);
        }
        try {
            int coupons = couponService.expireOverdue();
            log.info("优惠券过期收敛完成 count={}", coupons);
        } catch (Exception e) {
            log.error("优惠券过期收敛失败", e);
        }
    }
}
