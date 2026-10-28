package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 桌面助手回复
 */
@Data
@Schema(description = "桌面助手回复")
public class AssistantReplyVO {

    @Schema(description = "会话ID，后续提问回传可保持上下文")
    private String sessionId;

    @Schema(description = "助手消息ID")
    private Long messageId;

    @Schema(description = "回复文本（Markdown 安全，可直接渲染为纯文本）")
    private String reply;

    @Schema(description = "命中的意图编码")
    private String intent;

    @Schema(description = "意图中文说明")
    private String intentLabel;

    @Schema(description = "附带动作，前端按 type 渲染卡片或跳转")
    private Action action;

    @Schema(description = "推荐追问")
    private List<String> suggestions;

    @Schema(description = "是否使用了内置知识库兜底（未配置或调用大模型失败）")
    private boolean degraded;

    @Schema(description = "回复时间")
    private LocalDateTime time;

    /**
     * 前端动作描述
     */
    @Data
    @Schema(description = "助手动作")
    public static class Action {

        @Schema(description = "动作类型：NONE/TASK_LIST/TASK_DETAIL/QUOTA/VIP/FILE_LIST/ADMIN_STATS/NAVIGATE")
        private String type;

        @Schema(description = "按钮文案")
        private String label;

        @Schema(description = "跳转目标（type=NAVIGATE 时有效）")
        private String target;

        @Schema(description = "卡片数据")
        private Object data;
    }
}
