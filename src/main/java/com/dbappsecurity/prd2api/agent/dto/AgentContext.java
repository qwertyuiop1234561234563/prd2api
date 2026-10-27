package com.dbappsecurity.prd2api.agent.dto;

import com.dbappsecurity.prd2api.vo.RagChunkVO;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 流水线上下文：贯穿 4 个阶段
 */
@Data
@Builder
public class AgentContext {

    private String taskId;

    private String tenantId;

    private String userKey;

    private String modelCode;

    private String chunkStrategy;

    /** 原文（已清洗、必要时压缩） */
    private String docText;

    /** 原文是否被压缩（影响提示词中的置信度声明） */
    private boolean docCompressed;

    /** RAG 召回片段 */
    @Builder.Default
    private List<RagChunkVO> chunks = new ArrayList<>();

    /** 阶段输出 */
    private String stage1Output;

    private String stage2Output;

    private String stage3Output;

    /** 拼接后的 RAG 上下文文本 */
    public String chunkContext() {
        if (chunks == null || chunks.isEmpty()) {
            return "（无召回片段）";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RagChunkVO chunk = chunks.get(i);
            builder.append("[片段").append(i + 1).append(" | ").append(chunk.getSource()).append("]\n")
                    .append(chunk.getContent()).append("\n\n");
        }
        return builder.toString();
    }
}
