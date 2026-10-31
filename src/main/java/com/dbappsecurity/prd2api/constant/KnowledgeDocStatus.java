package com.dbappsecurity.prd2api.constant;

/**
 * 知识库文档索引状态机
 * <pre>
 * PENDING -> PARSING -> CHUNKING -> EMBEDDING -> COMPLETED
 *                                            \-> FAILED
 * </pre>
 * 参考 Dify / FastGPT：上传后先入队，异步解析与向量化，任一阶段失败都落到 FAILED 并保留原因。
 */
public enum KnowledgeDocStatus {

    /** 已入队，等待处理 */
    PENDING,
    /** 解析文档正文 */
    PARSING,
    /** 切分片段 */
    CHUNKING,
    /** 向量化并写入向量库 */
    EMBEDDING,
    /** 索引完成，可被检索 */
    COMPLETED,
    /** 处理失败，可重试 */
    FAILED;

    /**
     * 是否处于进行中的中间状态（前端据此轮询）
     */
    public static boolean running(String status) {
        return PENDING.name().equals(status) || PARSING.name().equals(status)
                || CHUNKING.name().equals(status) || EMBEDDING.name().equals(status);
    }
}
