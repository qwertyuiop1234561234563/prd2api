package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 答复证据链条目：每条结论对应一条可回溯证据（知识来源或系统数据）
 */
@Data
@Schema(description = "答复证据链")
public class AssistantCitationVO {

    @Schema(description = "对应轨迹步骤序号")
    private int stepNo;

    @Schema(description = "证据类型：KNOWLEDGE / TOOL / SUBAGENT / MEMORY")
    private String type;

    @Schema(description = "证据标题")
    private String title;

    @Schema(description = "证据来源：文档名 / 系统实时数据 / 专家子 Agent / 用户长期记忆")
    private String source;

    @Schema(description = "证据片段（截断展示）")
    private String snippet;

    @Schema(description = "证据片段指纹：来源与内容哈希前 8 位，用于跨轮次对齐")
    private String chunkId;

    @Schema(description = "该证据在答复中的起始字符下标，未引用为 -1")
    private int replyStart = -1;

    @Schema(description = "该证据在答复中的结束字符下标（不含），未引用为 -1")
    private int replyEnd = -1;

    @Schema(description = "证据被答复覆盖的比例，0~1")
    private double overlap;

    @Schema(description = "答复是否真的引用了该证据")
    private boolean verified;

    @Schema(description = "产生时间")
    private LocalDateTime time;
}
