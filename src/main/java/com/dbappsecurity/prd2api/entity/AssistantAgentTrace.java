package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 助手 Agent 轨迹：每次工具调用的参数、结果、耗时与成败，用于可观测与审计
 */
@Data
@TableName("assistant_agent_trace")
public class AssistantAgentTrace {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 一次提问对应一个 traceId */
    private String traceId;

    private String sessionId;

    private String tenantId;

    private String userId;

    private Integer stepNo;

    /** THINK / TOOL / KNOWLEDGE / MEMORY */
    private String stepType;

    private String toolName;

    private String toolArgs;

    private String toolResult;

    private Integer success;

    private Long latencyMs;

    private LocalDateTime createTime;
}
