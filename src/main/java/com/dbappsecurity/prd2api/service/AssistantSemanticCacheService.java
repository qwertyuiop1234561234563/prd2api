package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.vo.AgentStepVO;

import java.util.List;

/**
 * 语义缓存：相同/近义问题直接复用历史答复，降低 P95 延迟与 token 成本
 * <p>
 * 安全边界：按租户 + 用户隔离，只缓存“知识型、无动作、无个人实时数据”的问答；
 * 含任务 ID、指代上文、查询额度/任务/文件的问答一律不缓存，避免跨用户泄露与数据过期。
 */
public interface AssistantSemanticCacheService {

    /**
     * 查找语义命中的历史答复，未命中返回 null
     */
    Entry lookup(String question);

    /**
     * 判断本轮答复是否适合写入缓存
     */
    boolean cacheable(String question, AssistantAgentService.AgentOutcome outcome, String intentCode);

    /**
     * 写入缓存（内部做 embedding 与容量淘汰）
     */
    void put(String question, String reply, String mode, String intentCode, List<AgentStepVO> steps);

    /**
     * 命中的缓存条目
     *
     * @param score 与当前问题的余弦相似度
     */
    record Entry(String reply, String mode, String intent, List<AgentStepVO> steps, double score) {
    }
}
