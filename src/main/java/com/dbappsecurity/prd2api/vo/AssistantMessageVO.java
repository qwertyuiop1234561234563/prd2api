package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 桌面助手历史消息
 */
@Data
@Schema(description = "桌面助手历史消息")
public class AssistantMessageVO {

    @Schema(description = "消息ID")
    private Long id;

    @Schema(description = "角色 USER / ASSISTANT")
    private String role;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "助手消息命中的意图")
    private String intent;

    @Schema(description = "时间")
    private LocalDateTime createTime;
}
