package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 助手长期记忆：按用户沉淀的稳定事实（称呼、偏好、常用项目等）
 */
@Data
@TableName("assistant_user_memory")
public class AssistantUserMemory {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private String userId;

    /** 记忆键，同一用户下唯一 */
    private String memoryKey;

    /** 记忆内容 */
    private String memoryValue;

    /** 来源：AGENT（模型主动写入）/ RULE（规则抽取）/ USER（用户显式设置） */
    private String source;

    /** 命中次数，用于召回排序 */
    private Integer hitCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
