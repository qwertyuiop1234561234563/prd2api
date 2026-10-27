package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户资源配额
 */
@Data
@TableName("tenant_quota")
public class TenantQuota {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private Integer dailyUploadLimit;

    private Long dailyTokenLimit;

    private Integer concurrentTaskLimit;

    private Integer usedUploadCount;

    private Long usedTokenCount;

    private LocalDateTime resetTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
