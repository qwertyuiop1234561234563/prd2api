package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 桌面助手提问入参
 */
@Data
@Schema(description = "桌面助手提问入参")
public class AssistantChatDTO {

    @Schema(description = "会话ID，首次提问可不传，服务端创建后返回")
    @Size(max = 64, message = "sessionId 长度不能超过 64")
    private String sessionId;

    @NotBlank(message = "message 不能为空")
    @Size(max = 500, message = "单次提问不能超过 500 字")
    @Schema(description = "提问内容", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "我的解析任务到哪一步了？")
    private String message;
}
