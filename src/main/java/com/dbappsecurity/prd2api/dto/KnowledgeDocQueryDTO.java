package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 知识库文档分页查询入参
 */
@Data
@Schema(description = "知识库文档分页查询入参")
public class KnowledgeDocQueryDTO {

    @Min(value = 1, message = "pageNum 最小为 1")
    @Max(value = 1000, message = "pageNum 最大为 1000")
    @Schema(description = "页码，从 1 开始", defaultValue = "1")
    private int pageNum = 1;

    @Min(value = 1, message = "pageSize 最小为 1")
    @Max(value = 100, message = "pageSize 最大为 100")
    @Schema(description = "每页条数", defaultValue = "10")
    private int pageSize = 10;

    @Schema(description = "文件名关键字，模糊匹配")
    private String keyword;

    @Schema(description = "索引状态：PENDING/PARSING/CHUNKING/EMBEDDING/COMPLETED/FAILED")
    private String status;

    @Schema(description = "租户ID，仅管理员可用；普通用户强制使用当前租户")
    private String tenantId;
}
