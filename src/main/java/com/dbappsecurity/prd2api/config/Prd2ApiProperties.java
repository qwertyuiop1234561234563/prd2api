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
        private String baseUrl = "https://api.deepseek.com/v1";
        private String apiKey = "";
        private String modelName = "deepseek-chat";
        private String embeddingModel = "deepseek-embedding";
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
}
