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
}
