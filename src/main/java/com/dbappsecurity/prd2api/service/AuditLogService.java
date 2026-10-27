package com.dbappsecurity.prd2api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.dto.AuditQueryDTO;
import com.dbappsecurity.prd2api.entity.AuditLog;
import com.dbappsecurity.prd2api.vo.AuditLogVO;

/**
 * 操作审计服务
 */
public interface AuditLogService {

    /**
     * 落库一条审计日志，内部吞掉异常，避免影响主流程
     */
    void record(AuditLog auditLog);

    /**
     * 分页查询审计日志（租户隔离）
     */
    Page<AuditLogVO> page(AuditQueryDTO dto);

    /**
     * 清理过期审计日志
     *
     * @return 删除条数
     */
    int cleanExpired(int expireDay);
}
