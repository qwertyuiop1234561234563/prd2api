package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 桌面助手会话摘要
 */
@Data
@Schema(description = "桌面助手会话摘要")
public class AssistantSessionVO {

    @Schema(description = "会话ID")
    private String sessionId;

    @Schema(description = "会话标题")
    private String title;

    @Schema(description = "消息条数")
    private Integer messageCount;

    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;
}
