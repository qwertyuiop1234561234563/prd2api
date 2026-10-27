package com.dbappsecurity.prd2api.service;

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
     * 是否已配置可用 API Key
     */
    boolean available();
}
