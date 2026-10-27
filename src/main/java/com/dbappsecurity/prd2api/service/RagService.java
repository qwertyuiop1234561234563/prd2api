package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.vo.RagChunkVO;

import java.util.List;

/**
 * RAG 服务：任务级索引构建、召回、索引销毁
 */
public interface RagService {

    /**
     * 构建任务独立索引（租户 + 任务双重隔离）
     *
     * @param chunkStrategy 分块策略 fixed / semantic
     */
    void buildTaskIndex(String tenantId, String taskId, String text, String chunkStrategy);

    /**
     * 召回与问题最相关的片段
     */
    List<RagChunkVO> retrieve(String taskId, String query, int topK);

    /**
     * 删除任务索引与缓存
     */
    void dropTaskIndex(String taskId);

    /**
     * 向量库连通性
     */
    boolean chromaAvailable();
}
