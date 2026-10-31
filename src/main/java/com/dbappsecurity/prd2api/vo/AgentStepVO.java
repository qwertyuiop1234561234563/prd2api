package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 单步轨迹：思考、工具调用、知识检索或记忆写入
 */
@Data
@Schema(description = "Agent 轨迹步骤")
public class AgentStepVO {

    @Schema(description = "步骤序号，从 1 开始")
    private int stepNo;

    @Schema(description = "步骤类型：TOOL / KNOWLEDGE / MEMORY / THINK")
    private String type;

    @Schema(description = "工具名")
    private String toolName;

    @Schema(description = "步骤中文说明")
    private String title;

    @Schema(description = "工具入参（截断展示）")
    private String arguments;

    @Schema(description = "工具执行结果（截断展示）")
    private String result;

    @Schema(description = "证据来源：知识库文档名 / 系统实时数据 / 专家子 Agent / 用户长期记忆")
    private String source;

    @Schema(description = "耗时毫秒")
    private long latencyMs;

    @Schema(description = "是否成功")
    private boolean success;

    @Schema(description = "发生时间")
    private LocalDateTime time;
}
