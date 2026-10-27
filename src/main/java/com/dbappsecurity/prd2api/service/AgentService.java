package com.dbappsecurity.prd2api.service;

/**
 * Agent 流水线服务：4 阶段串行编排
 */
public interface AgentService {

    /**
     * 异步执行（入口：创建/重试任务后调用）
     */
    void executeAsync(String taskId);

    /**
     * 同步执行（内部含分布式锁，重复触发会被忽略）
     */
    void execute(String taskId);

    /**
     * 追加追问：基于已有报告回答，并回写到报告
     */
    String chat(String taskId, String question);
}
