package com.dbappsecurity.prd2api.constant;

/**
 * Redis Key 规范
 * <pre>
 * prd2api:task:lock:{taskId}              # 任务分布式锁
 * prd2api:task:context:{taskId}           # Agent 上下文缓存
 * prd2api:task:cancel:{taskId}            # 任务取消标记（流水线阶段间检查）
 * prd2api:rag:embedding:{chunkMd5}        # 向量缓存
 * prd2api:rag:collection:{taskId}         # 任务对应 Chroma collectionId
 * prd2api:rag:chunks:{taskId}              # 任务分块原文缓存
 * prd2api:doc:text:{fileMd5}               # 文档解析文本缓存
 * prd2api:limit:upload:{tenant}:{user}    # 上传限流（滑动窗口）
 * prd2api:limit:llm:{tenant}:{user}       # LLM 调用限流（滑动窗口）
 * prd2api:quota:daily:{tenantId}          # 租户配额计数器
 * prd2api:export:token:{exportToken}      # 导出临时凭证
 * prd2api:auth:token:{token}              # 登录令牌 -> userId
 * prd2api:auth:user:tokens:{userId}       # 用户全部有效令牌集合，用于改密/封号时统一踢下线
 * prd2api:coupon:{couponId}:stock         # 优惠券剩余库存（Lua 原子扣减）
 * prd2api:coupon:{couponId}:user:{userId} # 用户已领取数量（Lua 原子累加）
 * prd2api:limit:assistant:{tenant}:{user} # 桌面助手提问限流（滑动窗口）
 * prd2api:limit:knowledge:{tenant}:{user} # 知识库上传限流（滑动窗口）
 * prd2api:assistant:clean:lock            # 助手会话过期清理分布式锁
 * </pre>
 */
public final class RedisKeyConstant {

    private RedisKeyConstant() {
    }

    public static final String TASK_LOCK_PREFIX = "prd2api:task:lock:";
    public static final String TASK_CREATE_LOCK_PREFIX = "prd2api:task:create:lock:";
    public static final String TASK_CONTEXT_PREFIX = "prd2api:task:context:";
    public static final String TASK_CANCEL_PREFIX = "prd2api:task:cancel:";
    public static final String RAG_EMBEDDING_PREFIX = "prd2api:rag:embedding:";
    public static final String RAG_COLLECTION_PREFIX = "prd2api:rag:collection:";
    public static final String RAG_CHUNKS_PREFIX = "prd2api:rag:chunks:";
    public static final String DOC_TEXT_PREFIX = "prd2api:doc:text:";
    public static final String LIMIT_UPLOAD_PREFIX = "prd2api:limit:upload:";
    public static final String LIMIT_LLM_PREFIX = "prd2api:limit:llm:";
    public static final String QUOTA_DAILY_PREFIX = "prd2api:quota:daily:";
    public static final String EXPORT_TOKEN_PREFIX = "prd2api:export:token:";
    public static final String AUTH_TOKEN_PREFIX = "prd2api:auth:token:";
    public static final String AUTH_USER_TOKENS_PREFIX = "prd2api:auth:user:tokens:";
    /** 优惠券 Key 前缀，使用 {couponId} 作为 hash tag，保证库存与个人计数落在同一 Redis Cluster 槽位 */
    public static final String COUPON_PREFIX = "prd2api:coupon:";
    /** 桌面助手提问限流 */
    public static final String LIMIT_ASSISTANT_PREFIX = "prd2api:limit:assistant:";
    /** 知识库上传限流 */
    public static final String LIMIT_KNOWLEDGE_PREFIX = "prd2api:limit:knowledge:";
    /** 助手会话过期清理分布式锁 */
    public static final String ASSISTANT_CLEAN_LOCK = "prd2api:assistant:clean:lock";
    /** 助手知识库是否已完成向量化索引 */
    public static final String ASSISTANT_KB_INDEXED = "prd2api:assistant:kb:indexed";
    /** 危险工具人工确认票据（按租户+用户隔离） */
    public static final String ASSISTANT_CONFIRM_PREFIX = "prd2api:assistant:confirm:";
    /** 工具幂等键，防止重试或重复调用造成重复副作用 */
    public static final String ASSISTANT_TOOL_IDEM_PREFIX = "prd2api:assistant:tool:idem:";
    /** 评测任务互斥锁，防止并发刷 token */
    public static final String ASSISTANT_EVAL_LOCK = "prd2api:assistant:eval:lock";
    /** 会话历史摘要缓存，避免每轮重复压缩早期对话 */
    public static final String ASSISTANT_CTX_SUMMARY_PREFIX = "prd2api:assistant:ctx:summary:";
    /** 语义缓存（按租户+用户隔离，只缓存知识型问答） */
    public static final String ASSISTANT_SEMCACHE_PREFIX = "prd2api:assistant:semcache:";
    /** Agent 执行中断标记（按租户+用户隔离，值为会话 ID 或 * 表示取消当前运行） */
    public static final String ASSISTANT_CANCEL_PREFIX = "prd2api:assistant:cancel:";
    /** Agent 断点续跑检查点（按租户+用户+会话隔离） */
    public static final String ASSISTANT_CHECKPOINT_PREFIX = "prd2api:assistant:checkpoint:";
    /** 工具选择经验策略（按租户+用户隔离） */
    public static final String ASSISTANT_TOOL_POLICY_PREFIX = "prd2api:assistant:policy:";
    /** 失败反思教训（按租户+用户隔离，字段为工具名） */
    public static final String ASSISTANT_REFLECTION_PREFIX = "prd2api:assistant:reflection:";
    /** 失败样本回归用例（按租户+用户隔离，字段为用例 ID） */
    public static final String ASSISTANT_FAILURE_CASE_PREFIX = "prd2api:assistant:failurecase:";
    /** 用户对助手答复的反馈（按租户+用户隔离，字段为反馈 ID） */
    public static final String ASSISTANT_FEEDBACK_PREFIX = "prd2api:assistant:feedback:";
    /** 只读工具结果缓存（按租户+用户+工具+参数隔离，短 TTL） */
    public static final String ASSISTANT_TOOL_CACHE_PREFIX = "prd2api:assistant:tool:cache:";
    /** 工具级滑动窗口限流（按租户+用户+工具隔离，ZSET 存调用时间戳） */
    public static final String ASSISTANT_TOOL_RATE_PREFIX = "prd2api:assistant:tool:rate:";
    /** 工具调用样例沉淀（按租户+用户隔离，缓存注入提示词的 few-shot 块） */
    public static final String ASSISTANT_TOOL_EXAMPLE_PREFIX = "prd2api:assistant:tool:example:";
    /** 工具熔断状态快照（按租户+用户隔离，缓存各工具的 CLOSED/OPEN/HALF_OPEN） */
    public static final String ASSISTANT_TOOL_CIRCUIT_PREFIX = "prd2api:assistant:tool:circuit:";
    /** 半开探测票据（按租户+用户+工具隔离，冷却周期内只允许一次探测） */
    public static final String ASSISTANT_TOOL_CIRCUIT_PROBE_PREFIX = "prd2api:assistant:tool:circuit:probe:";
    /** 会话互斥锁（按租户+用户+会话隔离，同一会话同一时刻只允许一轮对话） */
    public static final String ASSISTANT_CHAT_LOCK_PREFIX = "prd2api:assistant:chat:lock:";

    public static String taskLock(String taskId) {
        return TASK_LOCK_PREFIX + taskId;
    }

    public static String taskCreateLock(String tenantId) {
        return TASK_CREATE_LOCK_PREFIX + tenantId;
    }

    public static String taskContext(String taskId) {
        return TASK_CONTEXT_PREFIX + taskId;
    }

    public static String taskCancel(String taskId) {
        return TASK_CANCEL_PREFIX + taskId;
    }

    public static String ragEmbedding(String chunkMd5) {
        return RAG_EMBEDDING_PREFIX + chunkMd5;
    }

    public static String ragCollection(String taskId) {
        return RAG_COLLECTION_PREFIX + taskId;
    }

    public static String ragChunks(String taskId) {
        return RAG_CHUNKS_PREFIX + taskId;
    }

    public static String docText(String fileMd5) {
        return DOC_TEXT_PREFIX + fileMd5;
    }

    public static String limitUpload(String tenantId, String userKey) {
        return LIMIT_UPLOAD_PREFIX + tenantId + ":" + userKey;
    }

    public static String limitLlm(String tenantId, String userKey) {
        return LIMIT_LLM_PREFIX + tenantId + ":" + userKey;
    }

    public static String quotaDaily(String tenantId) {
        return QUOTA_DAILY_PREFIX + tenantId;
    }

    public static String exportToken(String token) {
        return EXPORT_TOKEN_PREFIX + token;
    }

    public static String authToken(String token) {
        return AUTH_TOKEN_PREFIX + token;
    }

    public static String authUserTokens(String userId) {
        return AUTH_USER_TOKENS_PREFIX + userId;
    }

    public static String couponStock(Long couponId) {
        return COUPON_PREFIX + "{" + couponId + "}:stock";
    }

    public static String couponUserCount(Long couponId, String userId) {
        return COUPON_PREFIX + "{" + couponId + "}:user:" + userId;
    }

    public static String limitAssistant(String tenantId, String userKey) {
        return LIMIT_ASSISTANT_PREFIX + tenantId + ":" + userKey;
    }

    /**
     * 工具级限流 key：租户 + 用户 + 工具，防止单个用户刷爆某个下游工具
     */
    public static String limitAssistantTool(String tenantId, String userKey, String toolName) {
        return ASSISTANT_TOOL_RATE_PREFIX + tenantId + ":" + userKey + ":" + toolName;
    }

    public static String limitKnowledge(String tenantId, String userKey) {
        return LIMIT_KNOWLEDGE_PREFIX + tenantId + ":" + userKey;
    }

    public static String assistantCancel(String tenantId, String userKey) {
        return ASSISTANT_CANCEL_PREFIX + tenantId + ":" + userKey;
    }

    /**
     * 工具样例缓存 key：租户 + 用户
     */
    public static String assistantToolExample(String tenantId, String userKey) {
        return ASSISTANT_TOOL_EXAMPLE_PREFIX + tenantId + ":" + userKey;
    }

    /**
     * 工具熔断快照 key：租户 + 用户
     */
    public static String assistantCircuit(String tenantId, String userKey) {
        return ASSISTANT_TOOL_CIRCUIT_PREFIX + tenantId + ":" + userKey;
    }

    /**
     * 半开探测票据 key：租户 + 用户 + 工具
     */
    public static String assistantCircuitProbe(String tenantId, String userKey, String toolName) {
        return ASSISTANT_TOOL_CIRCUIT_PROBE_PREFIX + tenantId + ":" + userKey + ":" + toolName;
    }

    /**
     * 会话互斥锁 key：租户 + 用户 + 会话
     */
    public static String assistantChatLock(String tenantId, String userKey, String sessionId) {
        return ASSISTANT_CHAT_LOCK_PREFIX + tenantId + ":" + userKey + ":" + sessionId;
    }
}
