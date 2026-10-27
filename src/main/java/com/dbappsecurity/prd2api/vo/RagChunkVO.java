package com.dbappsecurity.prd2api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 召回片段
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "RAG 召回片段")
public class RagChunkVO {

    @Schema(description = "片段原文")
    private String content;

    @Schema(description = "来源标识：向量库 / 本地分块序号")
    private String source;

    @Schema(description = "相似度得分，0~1")
    private double score;
}
