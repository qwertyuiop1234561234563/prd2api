package com.dbappsecurity.prd2api.config;

import com.dbappsecurity.prd2api.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时清理：过期文件、过期报告缓存、过期导出临时文件
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ScheduledCleanConfig {

    private final FileService fileService;
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
}
