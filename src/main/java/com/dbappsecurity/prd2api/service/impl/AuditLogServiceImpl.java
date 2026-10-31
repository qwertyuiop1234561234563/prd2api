package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.dto.AuditQueryDTO;
import com.dbappsecurity.prd2api.entity.AuditLog;
import com.dbappsecurity.prd2api.mapper.AuditLogMapper;
import com.dbappsecurity.prd2api.service.AuditLogService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.AuditLogVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 操作审计实现：统一落库（失败不影响主流程） + 租户隔离查询 + 过期清理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private static final int ACTION_MAX = 64;
    private static final int TARGET_MAX = 128;
    private static final int URI_MAX = 256;
    private static final int ERROR_MAX = 500;
    /** 单次删除上限：大表清理分批执行，避免长事务与超大 undo log */
    private static final int DELETE_BATCH = 5000;

    private final AuditLogMapper auditLogMapper;

    @Override
    public void record(AuditLog auditLog) {
        try {
            auditLog.setCreateTime(LocalDateTime.now());
            auditLog.setTenantId(StrUtil.blankToDefault(auditLog.getTenantId(), "unknown"));
            auditLog.setUserKey(StrUtil.blankToDefault(auditLog.getUserKey(), "unknown"));
            auditLog.setUserRole(StrUtil.blankToDefault(auditLog.getUserRole(), TenantContextUtil.USER_ROLE));
            auditLog.setAction(StrUtil.maxLength(StrUtil.blankToDefault(auditLog.getAction(), "unknown"), ACTION_MAX));
            auditLog.setTarget(StrUtil.maxLength(auditLog.getTarget(), TARGET_MAX));
            auditLog.setUri(StrUtil.maxLength(auditLog.getUri(), URI_MAX));
            auditLog.setErrorMsg(StrUtil.maxLength(auditLog.getErrorMsg(), ERROR_MAX));
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            // 审计失败不影响业务主流程
            log.warn("审计日志写入失败 action={} target={}", auditLog.getAction(), auditLog.getTarget(), e);
        }
    }

    @Override
    public Page<AuditLogVO> page(AuditQueryDTO dto) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        if (!TenantContextUtil.isAdmin()) {
            wrapper.eq(AuditLog::getTenantId, TenantContextUtil.requireTenantId());
        } else if (StrUtil.isNotBlank(dto.getTenantId())) {
            wrapper.eq(AuditLog::getTenantId, dto.getTenantId());
        }
        wrapper.eq(StrUtil.isNotBlank(dto.getUserKey()), AuditLog::getUserKey, dto.getUserKey())
                .like(StrUtil.isNotBlank(dto.getAction()), AuditLog::getAction, dto.getAction())
                .eq(dto.getSuccess() != null, AuditLog::getSuccess, dto.getSuccess())
                .ge(dto.getStartTime() != null, AuditLog::getCreateTime, dto.getStartTime())
                .le(dto.getEndTime() != null, AuditLog::getCreateTime, dto.getEndTime())
                .orderByDesc(AuditLog::getCreateTime)
                .orderByDesc(AuditLog::getId);

        Page<AuditLog> page = auditLogMapper.selectPage(new Page<>(dto.getPageNum(), dto.getPageSize()), wrapper);
        Page<AuditLogVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(AuditLogVO::from).toList());
        return result;
    }

    @Override
    public int cleanExpired(int expireDay) {
        LocalDateTime before = LocalDateTime.now().minusDays(Math.max(1, expireDay));
        int total = 0;
        while (true) {
            int deleted = auditLogMapper.delete(new LambdaQueryWrapper<AuditLog>()
                    .lt(AuditLog::getCreateTime, before)
                    .last("limit " + DELETE_BATCH));
            total += deleted;
            if (deleted < DELETE_BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("清理过期审计日志 {} 条，阈值={} 天", total, Math.max(1, expireDay));
        }
        return total;
    }
}
