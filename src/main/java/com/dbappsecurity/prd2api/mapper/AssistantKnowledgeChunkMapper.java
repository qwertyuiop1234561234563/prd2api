package com.dbappsecurity.prd2api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbappsecurity.prd2api.entity.AssistantKnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 助手知识库分段 Mapper
 */
@Mapper
public interface AssistantKnowledgeChunkMapper extends BaseMapper<AssistantKnowledgeChunk> {

    /**
     * 批量写入分段，回填自增 ID（向量库以该 ID 作为向量主键）
     */
    int insertBatch(@Param("list") List<AssistantKnowledgeChunk> list);

    /**
     * 本地降级召回候选：仅取已启用且索引完成文档的分段
     *
     * @param limit 候选上限，避免一次加载全部知识
     */
    List<AssistantKnowledgeChunk> selectCandidates(@Param("tenantId") String tenantId,
                                                    @Param("limit") int limit);
}
