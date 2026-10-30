package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 助手长期记忆视图
 */
@Data
@Schema(description = "助手长期记忆")
public class AssistantMemoryVO {

    @Schema(description = "记忆ID")
    private Long id;

    @Schema(description = "记忆键")
    private String key;

    @Schema(description = "记忆内容")
    private String value;

    @Schema(description = "来源：AGENT / RULE / USER")
    private String source;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
