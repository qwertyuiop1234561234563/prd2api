package com.dbappsecurity.prd2api.service;

/**
 * 危险工具的人工确认票据：模型不能直接执行写/危险工具，需由用户在界面上确认一次
 */
public interface AssistantConfirmationService {

    /**
     * 生成确认票据，返回 confirmId（同一租户+用户内唯一，默认 5 分钟有效）
     */
    String create(String sessionId, String toolName, String arguments, String summary);

    /**
     * 消费确认票据：成功返回票据内容并从 Redis 删除（用后即焚，防重放）
     */
    PendingConfirm consume(String confirmId);

    /**
     * 待确认动作
     */
    record PendingConfirm(String confirmId, String sessionId, String toolName,
                          String arguments, String summary, String tenantId, String userId) {
    }
}
