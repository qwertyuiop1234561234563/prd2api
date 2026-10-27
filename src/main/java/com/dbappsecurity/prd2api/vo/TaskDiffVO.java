package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 报告版本对比视图
 */
@Data
@Schema(description = "报告版本对比视图")
public class TaskDiffVO {

    @Schema(description = "基线任务ID")
    private String baseTaskId;

    @Schema(description = "基线版本号")
    private Integer baseVersion;

    @Schema(description = "对比任务ID")
    private String compareTaskId;

    @Schema(description = "对比版本号")
    private Integer compareVersion;

    @Schema(description = "新增行数")
    private Integer addedLines;

    @Schema(description = "删除行数")
    private Integer removedLines;

    @Schema(description = "未变行数")
    private Integer unchangedLines;

    @Schema(description = "结果是否因过长被截断")
    private Boolean truncated;

    @Schema(description = "unified diff 文本，行首标记：空格未变 / + 新增 / - 删除")
    private String diffText;
}
