package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.entity.AssistantUserMemory;

import java.util.List;

/**
 * 助手长期记忆服务
 * <p>
 * 负责稳定事实的写入、召回与清理，全部按租户 + 用户隔离。
 */
public interface AssistantMemoryService {

    /**
     * 写入或更新一条记忆（同 key 覆盖）
     */
    MemoryItem save(String key, String value, String source);

    /**
     * 按相关度召回记忆，query 为空时按最近更新返回
     */
    List<MemoryItem> recall(String query, int limit);

    /**
     * 当前用户的记忆列表
     */
    List<AssistantUserMemory> list(int limit);

    /**
     * 删除本人一条记忆
     */
    void delete(Long id);

    /**
     * 从用户消息中规则抽取记忆（我叫 X、记住 X 等），返回本次新增/更新的条目
     */
    List<MemoryItem> extract(String message);

    /**
     * 当前用户记忆条数
     */
    long count();

    /**
     * 召回结果：命中相关度用于排序
     */
    record MemoryItem(Long id, String key, String value, double score) {
    }
}
