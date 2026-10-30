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
        private int readTimeout = 60000;
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
        /** 单轮 Agent 执行的总超时（毫秒），超时强制收口 */
        private long runTimeoutMs = 90000L;
        /** 单轮 Agent 的 token 预算，超出后停止继续决策 */
        private long maxTokenBudget = 30000L;
        /** 同一工具 + 相同参数允许重复执行的次数，超过判定为死循环 */
        private int toolRepeatLimit = 2;
        /** 是否允许委派子 Agent（专家子任务） */
        private boolean delegateEnabled = true;
        /** 子 Agent 最多决策步数 */
        private int subAgentSteps = 3;
    }
}
