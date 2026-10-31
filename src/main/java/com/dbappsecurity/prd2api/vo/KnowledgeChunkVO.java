package com.dbappsecurity.prd2api.vo;

import com.dbappsecurity.prd2api.entity.AssistantKnowledgeChunk;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 知识库分段展示对象（预览与检索结果复用）
 */
@Data
@Schema(description = "知识库分段")
public class KnowledgeChunkVO {

    @Schema(description = "分段ID")
    private Long id;

    @Schema(description = "所属文档ID")
    private String docId;

    @Schema(description = "文档内序号")
    private Integer chunkNo;

    @Schema(description = "引用来源标签")
    private String source;

    @Schema(description = "分段正文")
    private String content;

    @Schema(description = "字符数")
    private Integer charCount;

    @Schema(description = "相似度得分，0~1，仅检索结果返回")
    private double score;

    public static KnowledgeChunkVO from(AssistantKnowledgeChunk chunk) {
        KnowledgeChunkVO vo = new KnowledgeChunkVO();
        vo.setId(chunk.getId());
        vo.setDocId(chunk.getDocId());
        vo.setChunkNo(chunk.getChunkNo());
        vo.setSource(chunk.getSource());
        vo.setContent(chunk.getContent());
        vo.setCharCount(chunk.getCharCount());
        return vo;
    }
}
