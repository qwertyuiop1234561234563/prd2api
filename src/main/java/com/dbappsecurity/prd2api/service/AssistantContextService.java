package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.entity.AssistantChatMessage;

import java.util.List;
import java.util.Map;

/**
 * Agent 上下文工程：token 预算估算、早期对话摘要压缩、超预算截断
 * <p>
 * 目的：会话变长或知识片段变大时，token 成本不会线性膨胀；同时尽量保住早期关键事实，
 * 而不是简单丢弃最老的消息（截断会直接丢失“我叫什么”“任务 ID”这类关键信息）。
 */
public interface AssistantContextService {

    /**
     * 组装本轮送入模型的消息
     *
     * @param sessionId    会话 ID（用于复用历史摘要）
     * @param systemPrompt 系统提示词（已包含长期记忆）
     * @param history      最近会话消息，按时间正序
     * @param message      本轮用户消息
     */
    ContextResult build(String sessionId, String systemPrompt,
                        List<AssistantChatMessage> history, String message);

    /**
     * 组装结果
     *
     * @param systemPrompt       最终的 system 消息（可能追加历史摘要）
     * @param messages           历史 + 当前问题（不含 system）
     * @param rawTokens          压缩前估算 token
     * @param finalTokens        压缩后估算 token
     * @param compressed         是否触发了压缩/截断
     * @param summarizedMessages 被摘要压缩的消息条数
     * @param droppedMessages    被直接丢弃的消息条数
     */
    record ContextResult(String systemPrompt, List<Map<String, Object>> messages,
                         int rawTokens, int finalTokens, boolean compressed,
                         int summarizedMessages, int droppedMessages) {
    }
}
