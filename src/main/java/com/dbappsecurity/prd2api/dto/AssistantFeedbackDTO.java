package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户对某条助手答复的反馈
 */
@Data
@Schema(description = "助手答复反馈")
public class AssistantFeedbackDTO {

    @Schema(description = "会话ID")
    @NotBlank(message = "会话ID不能为空")
    private String sessionId;

    @Schema(description = "被评价的助手消息ID；不传则取该会话最新一条助手消息")
    private Long messageId;

    @Schema(description = "评价：UP 点赞 / DOWN 点踩")
    @NotBlank(message = "评价不能为空")
    private String rating;

    @Schema(description = "点踩原因：INACCURATE 不准确 / INCOMPLETE 不完整 / SLOW 太慢 / UNSAFE 不安全 / OTHER 其他")
    private String reason;

    @Schema(description = "补充说明，最多 200 字")
    @Size(max = 200, message = "补充说明最多 200 字")
    private String comment;
}
