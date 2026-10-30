package com.dbappsecurity.prd2api.service;

import java.util.List;
import java.util.Map;

/**
 * LLM 调用服务：统一封装超时、重试、熔断、token 统计
 */
public interface LlmInvokeService {

    /**
     * 调用对话模型
     *
     * @param modelCode 模型编码（default/deepseek-chat/...）
     * @return 模型输出文本
     */
    String chat(String systemPrompt, String userPrompt, String modelCode);

    /**
     * 带工具调用的对话（OpenAI 兼容 function calling）
     * <p>
     * messages 为完整消息列表，支持 system / user / assistant(tool_calls) / tool 四种角色，
     * 由 Agent 循环维护；toolsJson 为工具定义数组，为空时退化为普通对话。
     */
    ToolChatResult chatWithTools(List<Map<String, Object>> messages, String toolsJson, String modelCode);

    /**
     * 是否已配置可用 API Key
     */
    boolean available();

    /**
     * 是否支持工具调用（已配置 Key 且开关打开）
     */
    boolean toolCallingAvailable();

    /**
     * 一次对话的返回：文本 + 工具调用 + token 消耗
     */
    record ToolChatResult(String content, List<ToolCall> toolCalls, long tokens) {
    }

    /**
     * 模型发起的工具调用
     */
    record ToolCall(String id, String name, String arguments) {
    }
}
