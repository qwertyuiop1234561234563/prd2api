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

    @Schema(description = "是否开启 Plan-and-Execute 计划编排")
    private boolean planEnabled;

    @Schema(description = "计划最多步骤数")
    private int planMaxSteps;

    @Schema(description = "是否支持用户中断执行")
    private boolean cancelEnabled;

    @Schema(description = "是否开启 Supervisor 多专家并行委派")
    private boolean supervisorEnabled;

    @Schema(description = "是否开启 Actor-Critic 答复评审")
    private boolean criticEnabled;

    @Schema(description = "评审触发重写的分数下限（1~5）")
    private int criticMinScore;

    @Schema(description = "是否开启修正检索（Corrective RAG）：置信度不足时改写查询补检")
    private boolean correctiveRagEnabled;

    @Schema(description = "检索置信度下限，低于该值触发查询改写")
    private double retrievalMinConfidence;

    @Schema(description = "是否开启记忆反思：从对话中归纳稳定偏好")
    private boolean memoryReflectionEnabled;

    @Schema(description = "是否开启断点续跑：未完成任务可用“继续”恢复")
    private boolean checkpointEnabled;

    @Schema(description = "是否开启工具选择经验策略（从历史成功轨迹学习工具先验）")
    private boolean toolPolicyEnabled;

    @Schema(description = "是否开启失败反思：工具失败先诊断再重试并沉淀教训")
    private boolean reflectionEnabled;

    @Schema(description = "是否开启 PII 脱敏：手机号/身份证/银行卡/邮箱统一打码")
    private boolean piiMaskEnabled;

    @Schema(description = "是否开启工具输出契约校验：伪成功自动降级或改写参数重检")
    private boolean contractEnabled;

    @Schema(description = "是否开启答复溯源：证据字符区间与引用覆盖率")
    private boolean provenanceEnabled;

    @Schema(description = "是否开启轨迹回放：只读步骤重跑检测漂移")
    private boolean replayEnabled;

    @Schema(description = "是否开启失败样本挖掘：失败沉淀为回归用例")
    private boolean failureMiningEnabled;

    @Schema(description = "是否开启用户反馈闭环：点踩自动转回归用例")
    private boolean feedbackEnabled;

    @Schema(description = "是否开启决策审计：逐轮合规检查与评分")
    private boolean auditEnabled;

    @Schema(description = "是否开启只读工具结果缓存")
    private boolean toolCacheEnabled;

    @Schema(description = "只读工具结果缓存 TTL（秒）")
    private long toolCacheTtlSeconds;

    @Schema(description = "是否开启工具入参安全守卫")
    private boolean argumentGuardEnabled;

    @Schema(description = "工具级限流：每用户每分钟单个工具最多执行次数，0 表示关闭")
    private int toolRateLimitPerMinute;

    @Schema(description = "是否开启失败归因诊断")
    private boolean diagnoseEnabled;

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
