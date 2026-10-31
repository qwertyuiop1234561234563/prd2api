package com.dbappsecurity.prd2api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Prd2Api 业务配置
 * <p>
 * 全部参数外部化，密钥支持环境变量注入；@RefreshScope 支持热刷新，
 * 后续可无缝接入 Nacos / Apollo 配置中心。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "prd2api")
public class Prd2ApiProperties {

    private Storage storage = new Storage();
    private Chunk chunk = new Chunk();
    private Rag rag = new Rag();
    private Llm llm = new Llm();
    private Limit limit = new Limit();
    private Sensitive sensitive = new Sensitive();
    private Security security = new Security();
    private Clean clean = new Clean();
    private Export export = new Export();
    private Async async = new Async();
    private Auth auth = new Auth();
    private AdminInit adminInit = new AdminInit();
    private Assistant assistant = new Assistant();
    private Agent agent = new Agent();
    private Knowledge knowledge = new Knowledge();

    @Data
    public static class Storage {
        /** local / oss */
        private String mode = "local";
        private String localPath = "./data/upload";
        private String ossEndpoint = "";
        private String ossAccessKey = "";
        private String ossSecretKey = "";
        private String ossBucket = "";
    }

    @Data
    public static class Chunk {
        /** fixed / semantic */
        private String strategy = "semantic";
        private int fixedSize = 800;
        private int overlap = 150;
        /** 单次送入 LLM 的最大字符数，超出走压缩 */
        private int maxLlmChars = 60000;
        /** 解析文本写入 Redis 缓存的最大字符数，超出则不入缓存，避免大文档打爆 Redis */
        private int maxCacheChars = 200000;
    }

    @Data
    public static class Rag {
        private boolean enable = true;
        private String chromaUrl = "http://127.0.0.1:8000";
        private String chromaTenant = "default_tenant";
        private String chromaDatabase = "default_database";
        private String collectionPrefix = "prd2api";
        private int topK = 4;
    }

    @Data
    public static class Llm {
        /** 默认指向智谱 GLM-5.3-Flash（新用户免费资源包），可换任意 OpenAI 兼容网关 */
        private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
        private String apiKey = "";
        private String modelName = "glm-5.3-flash";
        private String embeddingModel = "embedding-3";
        /** 是否允许模型发起工具调用（function calling） */
        private boolean toolCalling = true;
        private int connectTimeout = 30000;
        /** 推理型模型生成长文本耗时较长，默认留足 5 分钟 */
        private int readTimeout = 300000;
        private int maxRetry = 2;
        private long retryBackoffMs = 800;
        private double temperature = 0.2D;
    }

    @Data
    public static class Limit {
        private int uploadPerMinute = 20;
        private int llmPerMinute = 60;
    }

    @Data
    public static class Sensitive {
        private boolean enable = true;
        private List<String> words = new ArrayList<>();
    }

    @Data
    public static class Security {
        /** IP 黑名单，命中直接拒绝 */
        private List<String> ipBlacklist = new ArrayList<>();
        /** 接口路径黑名单（前缀匹配，如 /api/rag） */
        private List<String> pathBlacklist = new ArrayList<>();
        /** 允许的跨域来源，默认 * 仅用于开发；生产建议配置具体域名 */
        private List<String> allowedOrigins = new ArrayList<>();
        /** 是否强制要求 X-Tenant-Id / X-User-Key，true 时缺失直接 401 */
        private boolean requireIdentity = false;
        /** 是否强制要求登录态（Bearer Token），true 时不再接受 Header 身份透传 */
        private boolean requireLogin = false;
        /** 是否信任 X-Forwarded-For 解析客户端真实 IP；仅在请求必经可信代理时开启，否则可被伪造绕过 IP 黑名单 */
        private boolean trustForwardedFor = false;
    }

    @Data
    public static class Clean {
        private boolean enable = true;
        private int expireDay = 7;
        private String cron = "0 30 3 * * ?";
    }

    @Data
    public static class Export {
        private int tokenExpireSeconds = 300;
        private String dir = "./data/export";
    }

    @Data
    public static class Async {
        private int fileCore = 4;
        private int fileMax = 8;
        private int fileQueue = 200;
        private int llmCore = 3;
        private int llmMax = 6;
        private int llmQueue = 100;
        /** SSE 进度推送调度线程数 */
        private int streamThreads = 4;
        /** 单实例 SSE 最大并发连接数，超出直接返回错误事件 */
        private int streamMaxConnections = 200;
    }

    @Data
    public static class Auth {
        /** 登录令牌有效期（秒），每次请求滑动续期 */
        private long tokenTtlSeconds = 43200L;
        /** 连续登录失败上限，达到后锁定账号 */
        private int loginFailLimit = 5;
        /** 账号锁定分钟数 */
        private int lockMinutes = 15;
        /** 允许自助注册的租户白名单，其他租户账号只能由管理员创建 */
        private List<String> selfRegisterTenants = new ArrayList<>(List.of("default"));
    }

    /**
     * 内置管理员初始化：首次启动创建管理员账号，并开通最高等级 VIP
     */
    @Data
    public static class AdminInit {
        /** 是否启用启动初始化 */
        private boolean enabled = true;
        /** 所属租户 */
        private String tenantId = "default";
        /** 登录账号 */
        private String username = "admin";
        /** 初始密码，为空则跳过初始化；已存在账号不会覆盖密码 */
        private String password = "";
        /** 指定套餐编码，留空自动取等级最高的可开通套餐 */
        private String planCode = "";
        /** 内置管理员 VIP 有效期天数，0 表示沿用套餐自身有效天数 */
        private int vipDays = 3650;
    }

    /**
     * 桌面助手：页面小人对话框
     */
    @Data
    public static class Assistant {
        /** 是否启用助手接口 */
        private boolean enabled = true;
        /** 每人每分钟提问上限（滑动窗口），<=0 不限流 */
        private int rateLimitPerMinute = 20;
        /** 携带多少条历史消息作为多轮上下文 */
        private int historyLimit = 6;
        /** 会话保留天数，超期由定时任务清理 */
        private int sessionExpireDays = 30;
        /** 会话清理 cron */
        private String cleanCron = "0 0 5 * * ?";
        /** 同一会话是否串行执行：防止双击并发导致历史、检查点与记忆互相覆盖 */
        private boolean chatLockEnabled = true;
        /** 会话锁有效期（秒），进程异常退出后自动释放 */
        private int chatLockSeconds = 90;
        /** 是否启用并发舱壁：限制同时执行的问答轮数，超限快速失败，避免线程池与下游被瞬时流量打满 */
        private boolean bulkheadEnabled = true;
        /** 全实例同时执行的问答轮数上限 */
        private int bulkheadGlobalLimit = 32;
        /** 单租户同时执行的问答轮数上限，防止单个租户挤占其他租户容量 */
        private int bulkheadTenantLimit = 8;
        /** 获取舱壁许可的等待毫秒数，0 表示立即失败（快速失败） */
        private long bulkheadWaitMs = 0L;
    }

    /**
     * 桌面助手 Agent：工具调用、长期记忆、知识库
     */
    @Data
    public static class Agent {
        /** 是否启用 Agent 循环；关闭后退化为规则问答 */
        private boolean enabled = true;
        /** 单轮对话最多工具调用步数，防止无限循环 */
        private int maxSteps = 6;
        /** 注入系统提示词的长期记忆条数 */
        private int memoryInjectLimit = 5;
        /** 单用户长期记忆最多保留条数 */
        private int memoryMaxFacts = 200;
        /** 知识库召回条数 */
        private int knowledgeTopK = 3;
        /** 是否落库 Agent 轨迹（工具调用审计） */
        private boolean tracePersist = true;
        /** 是否并行执行同一轮的多个工具调用 */
        private boolean parallelTools = true;
        /** 单个工具执行超时（毫秒） */
        private long toolTimeoutMs = 8000L;
        /** 工具失败重试次数（写操作靠幂等键保证不重复执行） */
        private int toolRetry = 1;
        /** 危险操作确认票据有效期（秒） */
        private int confirmTtlSeconds = 300;
        /** 是否开启提示词注入检测（命中后写操作强制人工确认） */
        private boolean injectionGuard = true;
        /** 工具执行线程池核心线程数 */
        private int toolCore = 4;
        /** 单轮上下文 token 预算，超出后对早期对话做摘要压缩 */
        private int contextTokenBudget = 6000;
        /** 压缩时完整保留的最近消息条数 */
        private int contextKeepRecent = 4;
        /** 是否启用早期对话摘要压缩；关闭后仅做超预算截断 */
        private boolean contextSummary = true;
        /** 历史摘要最大字数 */
        private int contextSummaryMaxChars = 800;
        /** 是否开启回答接地自检（数值出处与知识来源） */
        private boolean selfCheck = true;
        /** 自检未通过时是否让模型基于证据重写一次 */
        private boolean selfCheckRetry = true;
        /** 是否开启语义缓存（仅缓存无个人数据、无动作的知识型问答） */
        private boolean semanticCache = true;
        /** 语义缓存命中阈值（余弦相似度） */
        private double semanticCacheThreshold = 0.93D;
        /** 语义缓存条目有效期（秒） */
        private int semanticCacheTtlSeconds = 1800;
        /** 单用户语义缓存最多保留条数 */
        private int semanticCacheMaxEntries = 100;
        /** 是否开启工具动态检索：按问题语义只注入相关工具，避免工具变多后 prompt 膨胀 */
        private boolean toolRouting = true;
        /** 动态检索注入的工具数量上限 */
        private int toolTopK = 7;
        /** 工具检索的余弦相似度下限，低于该值不注入（核心工具始终保留） */
        private double toolMinScore = 0.2D;
        /** 单轮 Agent 执行的总超时（毫秒），超时强制收口；推理型模型需放宽到分钟级 */
        private long runTimeoutMs = 180000L;
        /** 单轮 Agent 的 token 预算，超出后停止继续决策 */
        private long maxTokenBudget = 30000L;
        /** 同一工具 + 相同参数允许重复执行的次数，超过判定为死循环 */
        private int toolRepeatLimit = 2;
        /** 是否允许委派子 Agent（专家子任务） */
        private boolean delegateEnabled = true;
        /** 子 Agent 最多决策步数 */
        private int subAgentSteps = 3;
        /** 是否开启 Plan-and-Execute：复合任务先出计划再执行 */
        private boolean planEnabled = true;
        /** 计划最多步骤数 */
        private int planMaxSteps = 5;
        /** 计划失败后允许重规划的次数上限 */
        private int planReplanLimit = 1;
        /** 用户中断标记有效期（秒），超过后自动失效，避免误杀下一轮 */
        private int cancelMarkerTtlSeconds = 300;
        /** 是否开启 Supervisor 并行委派：同一轮多个专家子任务并发执行后汇总 */
        private boolean supervisorParallel = true;
        /** 单个子 Agent 的执行超时（毫秒），超时即取消并标记缺失 */
        private long supervisorTimeoutMs = 60000L;
        /** 单个子 Agent 的 token 预算，超出后停止继续决策，避免单个专家吃光全局预算 */
        private long subAgentTokenBudget = 12000L;
        /** 是否开启 Actor-Critic 自反思：用 LLM 评审答复质量，低分触发一次重写 */
        private boolean criticEnabled = true;
        /** 评审触发重写的分数下限（忠实/完整/可执行三项，1~5 分） */
        private int criticMinScore = 4;
        /** 评审未通过时是否允许基于评审意见重写一次 */
        private boolean criticRewrite = true;
        /** 是否开启 Corrective RAG：检索置信度不足时改写查询并多跳补检 */
        private boolean correctiveRag = true;
        /** 检索置信度下限，低于该值触发查询改写（0~1，向量分与查询词覆盖率取较大值） */
        private double retrievalMinConfidence = 0.45D;
        /** 修正检索最多跳数，1 表示只做首跳，2 表示补一跳 */
        private int retrievalMaxHops = 2;
        /** 单次查询改写最多生成的子查询数 */
        private int retrievalRewriteLimit = 2;
        /** 是否开启记忆反思：从对话中归纳稳定偏好并写入长期记忆 */
        private boolean memoryReflection = true;
        /** 单轮反思最多写入的偏好条数 */
        private int memoryReflectionMaxFacts = 2;
        /** 是否开启断点续跑：未完成的任务保存检查点，用户说“继续”时恢复 */
        private boolean checkpointEnabled = true;
        /** 检查点有效期（秒） */
        private int checkpointTtlSeconds = 1800;
        /** 检查点保留的已完成步骤上限 */
        private int checkpointMaxSteps = 10;
        /** 是否开启工具选择经验策略：从历史成功轨迹学习“问法 → 工具”先验 */
        private boolean toolPolicyEnabled = true;
        /** 经验策略命中阈值（问法 bigram 覆盖率下限） */
        private double toolPolicyMinScore = 0.4D;
        /** 单用户经验条目上限 */
        private int toolPolicyMaxEntries = 200;
        /** 是否开启失败反思：工具失败后先诊断再重试，并沉淀教训 */
        private boolean reflectionEnabled = true;
        /** 是否开启 PII 脱敏：手机号/身份证/银行卡/邮箱不打码不外发、不落库 */
        private boolean piiMaskEnabled = true;
        /** 是否开启工具输出契约校验：伪成功结果自动降级或改写参数重检 */
        private boolean contractEnabled = true;
        /** 是否开启答复溯源：证据在答复中的字符区间与引用覆盖率 */
        private boolean provenanceEnabled = true;
        /** 是否开启轨迹回放：只读步骤按原参数重跑，检测行为漂移 */
        private boolean replayEnabled = true;
        /** 是否开启失败样本挖掘：失败轨迹沉淀为可重放的回归用例 */
        private boolean failureMiningEnabled = true;
        /** 是否开启用户反馈闭环：点踩自动转回归用例 */
        private boolean feedbackEnabled = true;
        /** 是否开启决策审计：逐轮校验合规规则并给出评分 */
        private boolean auditEnabled = true;
        /** 是否开启只读工具结果缓存：短 TTL 内同参数复用结果 */
        private boolean toolCacheEnabled = true;
        /** 只读工具结果缓存 TTL（秒） */
        private long toolCacheTtlSeconds = 30;
        /** 是否开启工具入参安全守卫：注入内容、越界数值、非法 ID 执行前直接拒绝 */
        private boolean argumentGuardEnabled = true;
        /** 工具级限流：每用户每分钟单个工具最多执行次数，<=0 表示关闭 */
        private int toolRateLimitPerMinute = 30;
        /** 是否开启失败归因诊断：按轨迹聚合失败原因并给出修复建议 */
        private boolean diagnoseEnabled = true;
        /** 记忆新鲜度阈值（天），超过该天数未更新视为陈旧记忆 */
        private int memoryStaleDays = 30;
        /** 记忆冲突判定阈值：记忆键 bigram 相似度下限，超过且取值不同视为同一主题的矛盾记忆 */
        private double memoryConflictSimilarity = 0.3D;
        /** 是否开启 PII 存量泄漏审计：扫描历史消息/轨迹中的明文个人信息并支持一键修复 */
        private boolean piiAuditEnabled = true;
        /** 工具健康看板：成功率 SLO 下限，低于该值判 WARN */
        private double toolHealthMinSuccessRate = 0.95D;
        /** 工具健康看板：P95 延迟 SLO 上限（毫秒），超过该值判 WARN */
        private long toolHealthP95Ms = 3000L;
        /** 工具健康看板：调用次数低于该值不做判级，避免小样本误报 */
        private int toolHealthMinCalls = 5;
        /** 是否开启工具调用样例沉淀：从历史成功轨迹挖掘参数格式并注入提示词 */
        private boolean toolExampleEnabled = true;
        /** 样例挖掘窗口（天） */
        private int toolExampleDays = 30;
        /** 单工具最多注入的样例条数 */
        private int toolExampleMaxPerTool = 1;
        /** 单次最多注入的样例总条数 */
        private int toolExampleMaxTotal = 6;
        /** 工具累计成功次数达到该值才产出样例 */
        private int toolExampleMinSuccess = 3;
        /** 样例块缓存 TTL（秒） */
        private long toolExampleTtlSeconds = 600L;
        /** 是否开启会话行为异常检测（中位数 + MAD 稳健离群） */
        private boolean anomalyEnabled = true;
        /** 稳健 z 分数告警阈值 */
        private double anomalyZThreshold = 3.0D;
        /** 会话数达到该值才启用 z 分数，否则仅用绝对阈值 */
        private int anomalyMinSessions = 5;
        /** 是否开启计划校验：模型产出的计划在执行前做可执行性检查并自动修复 */
        private boolean planValidationEnabled = true;
        /** 是否开启工具依赖熔断：近期失败率过高的工具快速失败，避免反复重试放大故障 */
        private boolean circuitEnabled = true;
        /** 熔断统计窗口：每个工具只看最近多少次调用 */
        private int circuitWindowCalls = 20;
        /** 窗口内调用数低于该值不做熔断判定，避免小样本误伤 */
        private int circuitMinCalls = 5;
        /** 失败率达到该值触发熔断 */
        private double circuitFailureRate = 0.6D;
        /** 熔断冷却时间（秒），到期后进入半开状态只放行一次探测 */
        private long circuitCooldownSeconds = 30L;
        /** 熔断状态缓存 TTL（秒），避免每次工具调用都查库算状态 */
        private long circuitCacheTtlSeconds = 30L;
        /** 是否开启复杂度自适应模型路由：简单问题走轻模型，复杂问题走强模型 */
        private boolean routeEnabled = true;
        /** 低于该分数判为简单问题 */
        private int routeSimpleThreshold = 35;
        /** 达到该分数判为复杂问题 */
        private int routeComplexThreshold = 65;
        /** 简单问题使用的模型编码（default 跟随全局配置） */
        private String routeSimpleModel = "default";
        /** 标准问题使用的模型编码 */
        private String routeStandardModel = "default";
        /** 复杂问题使用的模型编码，配置多模型网关后可切换为 deepseek-reasoner 等 */
        private String routeComplexModel = "default";
        /** 是否开启目标漂移自检：执行若干步后回看路径是否仍围绕用户目标 */
        private boolean goalDriftEnabled = true;
        /** 参与漂移判定的最小步骤数，太少不判定 */
        private int goalDriftMinSteps = 3;
        /** 聚焦比例达到该值判为 FOCUSED */
        private double goalDriftFocusThreshold = 0.6D;
        /** 聚焦比例低于该值判为 DRIFTED，介于两者之间判为 WEAK */
        private double goalDriftWeakThreshold = 0.35D;
        /** 是否开启系统提示词 A/B 实验 */
        private boolean promptAbEnabled = true;
        /** 实验名：变更实验名会重新分桶，务必与版本号一起发布 */
        private String promptAbExperiment = "assistant-system-v2";
        /** 实验组 B 的流量百分比 0~100 */
        private int promptAbPercent = 50;
        /** 只读工具结果是否启用进程内一级缓存（L1），降低热点参数对 Redis 的重复读取 */
        private boolean hotCacheEnabled = true;
        /** 一级缓存的本地存活秒数，应远小于 Redis 缓存 TTL，避免多实例间长时间不一致 */
        private int hotCacheTtlSeconds = 5;
        /** 一级缓存最大条目数，超出后先清理过期条目，仍超出则淘汰最旧条目 */
        private int hotCacheMaxEntries = 5000;
    }

    /**
     * 助手知识库：租户级离线文档上传与索引
     */
    @Data
    public static class Knowledge {
        /** 每个租户最多保留的文档数 */
        private int maxDocsPerTenant = 200;
        /** 单个知识文档大小上限（MB），同时受当前套餐额度约束 */
        private int maxDocSizeMb = 20;
        /** 单文档分段上限，超出提示拆分，避免单文档吃满向量库 */
        private int maxChunksPerDoc = 2000;
        /** 向量化批量写入条数 */
        private int embedBatchSize = 16;
        /** 向量库不可用时本地召回的最大候选分段数 */
        private int maxLocalCandidates = 2000;
        /** 每人每分钟知识库上传次数 */
        private int uploadPerMinute = 10;
    }
}
