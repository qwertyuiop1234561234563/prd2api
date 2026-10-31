package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.vo.RagChunkVO;

import java.util.List;

/**
 * 知识检索服务：在基础召回之上做置信度评估与查询修正
 * <p>
 * 目的不是“多召回几条”，而是让检索能自我纠错：首跳置信度不足时改写查询补一跳，
 * 并把“改写了什么、置信度从多少到多少、证据够不够”透出给上层，避免模型拿低质片段硬答。
 */
public interface AssistantRetrievalService {

    /**
     * 检索平台知识
     *
     * @param query 用户问题或工具入参
     * @param topK  期望返回的片段条数
     */
    Retrieval retrieve(String query, int topK);

    /**
     * 检索结果
     *
     * @param chunks         去重排序后的片段
     * @param queries        实际执行过的查询（含改写子查询）
     * @param hops           检索跳数，1 表示只做首跳
     * @param rewritten      是否触发查询改写
     * @param baseConfidence 首跳置信度
     * @param confidence     最终置信度
     * @param sufficient     证据是否足以支撑答复
     * @param note           给模型与审计看的一句话说明
     */
    record Retrieval(List<RagChunkVO> chunks, List<String> queries, int hops, boolean rewritten,
                     double baseConfidence, double confidence, boolean sufficient, String note) {

        public static Retrieval empty() {
            return new Retrieval(List.of(), List.of(), 1, false, 0.0D, 0.0D, false,
                    "未检索到相关片段，答复需说明无法确认");
        }
    }
}
