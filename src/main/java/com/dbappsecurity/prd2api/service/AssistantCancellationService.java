package com.dbappsecurity.prd2api.service;

/**
 * Agent 执行中断：前端发一个“停止”信号，Agent 在下个决策点检查并收口
 * <p>
 * 采用 Redis 标记而不是中断线程，原因是模型调用本身不可抢占。
 * 标记带写入时间戳，只对本轮开始之后发起的取消生效，因此不会误伤后续对话与评测批跑。
 */
public interface AssistantCancellationService {

    /**
     * 请求中断某会话正在执行的一轮；sessionId 为空表示中断当前用户正在运行的轮次
     */
    void request(String sessionId);

    /**
     * 本轮（startedAt 开始）是否已被请求中断
     *
     * @param sessionId 当前会话，可为空
     * @param startedAt 本轮开始时间（毫秒），早于该时间的标记视为针对历史轮次，不生效
     */
    boolean requested(String sessionId, long startedAt);
}
