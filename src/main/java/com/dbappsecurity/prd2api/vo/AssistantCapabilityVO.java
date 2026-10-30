package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 助手能力清单：用于前端展示 Agent 是否在线、可用工具、记忆与知识库规模
 */
@Data
@Schema(description = "助手能力清单")
public class AssistantCapabilityVO {

    @Schema(description = "总开关是否开启")
    private boolean agentEnabled;

    @Schema(description = "是否配置了大模型 Key")
    private boolean llmAvailable;

    @Schema(description = "是否具备工具调用能力")
    private boolean toolCalling;

    @Schema(description = "当前模式：AGENT / RULE")
    private String mode;

    @Schema(description = "单轮最大工具步数")
    private int maxSteps;

    @Schema(description = "已注册工具数")
    private int toolCount;

    @Schema(description = "工具清单")
    private List<ToolItem> tools;

    @Schema(description = "当前用户长期记忆条数")
    private long memoryCount;

    @Schema(description = "知识库文档数")
    private int knowledgeDocs;

    @Schema(description = "知识库是否启用向量检索")
    private boolean knowledgeVectorEnabled;

    @Schema(description = "上下文 token 预算，超出后摘要压缩早期对话")
    private int contextTokenBudget;

    @Schema(description = "是否开启回答接地自检")
    private boolean selfCheckEnabled;

    @Schema(description = "是否开启语义缓存")
    private boolean semanticCacheEnabled;

    @Schema(description = "语义缓存余弦相似度阈值")
    private double semanticCacheThreshold;

    @Schema(description = "是否开启工具动态检索（Tool RAG）")
    private boolean toolRoutingEnabled;

    @Schema(description = "工具动态检索 top-k")
    private int toolTopK;

    @Schema(description = "单轮执行时限（毫秒）")
    private long runTimeoutMs;

    @Schema(description = "单轮 token 预算")
    private long maxTokenBudget;

    @Schema(description = "是否开启子 Agent 委派")
    private boolean delegateEnabled;

    @Schema(description = "可用专家子 Agent")
    private List<String> experts;

    @Data
    @Schema(description = "工具项")
    public static class ToolItem {

        @Schema(description = "工具名")
        private String name;

        @Schema(description = "工具说明")
        private String title;

        @Schema(description = "风险等级：READ / WRITE / DANGER")
        private String risk;

        public ToolItem(String name, String title, String risk) {
            this.name = name;
            this.title = title;
            this.risk = risk;
        }
    }
}
