package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志
 * <p>
 * 通过 AOP 切面记录关键接口的调用人、目标、耗时与结果，满足内部审计与问题追溯要求。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private String userKey;

    private String userRole;

    /** 操作动作，如 任务创建 / 文件上传 */
    private String action;

    /** 操作对象，默认取第一个字符串入参（通常是 taskId / fileMetaId） */
    private String target;

    private String httpMethod;

    private String uri;

    private String clientIp;

    /** 1 成功 0 失败 */
    private Integer success;

    private Long costMs;

    private String traceId;

    private String errorMsg;

    private LocalDateTime createTime;
}
