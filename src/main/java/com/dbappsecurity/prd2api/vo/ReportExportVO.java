package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 报告导出结果：返回一次性下载凭证
 */
@Data
@Schema(description = "报告导出结果")
public class ReportExportVO {

    @Schema(description = "任务ID")
    private String taskId;

    @Schema(description = "导出格式 md/html/docx")
    private String format;

    @Schema(description = "导出文件名")
    private String fileName;

    @Schema(description = "一次性临时凭证，有效期默认 300 秒")
    private String exportToken;

    @Schema(description = "下载地址，携带 exportToken，无需额外 Header 鉴权")
    private String downloadUrl;

    @Schema(description = "凭证有效期（秒）")
    private Integer expireSeconds;
}
