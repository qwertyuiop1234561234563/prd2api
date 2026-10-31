package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.service.AssistantMemoryService.MemoryItem;

import java.util.List;

/**
 * 记忆反思服务：从对话中归纳可长期复用的稳定偏好
 * <p>
 * 与规则抽取（记住 XXX）互补：规则只能覆盖显式指令，反思覆盖“以后都用表格、先给结论再看细节”
 * 这类隐含偏好，把一次性请求和寒暄排除在外，避免记忆被噪声污染。
 */
public interface AssistantMemoryReflectionService {

    /**
     * 从用户发言中提炼稳定偏好并写入长期记忆，返回本次新增/更新的条目
     */
    List<MemoryItem> reflect(String userMessage);

    /**
     * 是否命中潜在偏好表达的触发条件，用于控制额外模型调用
     */
    boolean triggered(String userMessage);
}
