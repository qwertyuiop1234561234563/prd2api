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

    @Schema(description = "是否使用了规则或内置知识库兜底（未使用大模型工具循环）")
    private boolean degraded;

    @Schema(description = "执行模式：AGENT（模型工具循环）/ RULE（规则通道）/ FAQ（知识库兜底）")
    private String mode;

    @Schema(description = "本轮消耗 token")
    private long tokenUsed;

    @Schema(description = "Agent 轨迹：工具调用、知识检索、记忆写入、上下文压缩与接地自检")
    private List<AgentStepVO> steps;

    @Schema(description = "是否命中语义缓存（命中时本轮不调用大模型）")
    private boolean cacheHit;

    @Schema(description = "语义缓存与当前问题的余弦相似度，未命中为 0")
    private double cacheScore;

    @Schema(description = "答复是否通过接地自检；未做自检时默认为 true")
    private boolean grounded;

    @Schema(description = "接地自检未通过的说明，通过时为 null")
    private String groundingNote;

    @Schema(description = "答复证据链：每条结论对应的知识来源或系统数据")
    private List<AssistantCitationVO> citations;

    @Schema(description = "证据引用覆盖率：被答复实际引用的证据数 / 可用证据数")
    private double citationCoverage;

    @Schema(description = "被答复实际引用的证据条数")
    private int citationVerified;

    @Schema(description = "本轮可用证据总条数")
    private int citationTotal;

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
