package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 评审待确认条目
 */
@Data
@Schema(description = "评审待确认条目")
public class ReviewIssueVO {

    @Schema(description = "类型：PENDING 待确认 / CONFLICT 冲突 / CONFIRMED 确定")
    private String type;

    @Schema(description = "类型中文名")
    private String label;

    @Schema(description = "条目内容")
    private String content;

    @Schema(description = "所属章节标题")
    private String section;

    @Schema(description = "在报告中的行号，从 1 开始")
    private Integer lineNo;

    @Schema(description = "原始行内容")
    private String raw;
}
