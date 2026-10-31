package com.dbappsecurity.prd2api.vo;

import com.dbappsecurity.prd2api.entity.AssistantKnowledgeDoc;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档展示对象
 */
@Data
@Schema(description = "知识库文档")
public class KnowledgeDocVO {

    @Schema(description = "文档ID")
    private String docId;

    @Schema(description = "文件名")
    private String docName;

    @Schema(description = "文件后缀")
    private String fileSuffix;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "切分策略")
    private String chunkStrategy;

    @Schema(description = "正文字符数")
    private Integer charCount;

    @Schema(description = "分段数")
    private Integer chunkCount;

    @Schema(description = "索引状态")
    private String status;

    @Schema(description = "失败原因")
    private String errorMsg;

    @Schema(description = "是否启用检索")
    private boolean enabled;

    @Schema(description = "是否已建立向量索引")
    private boolean vectorIndexed;

    @Schema(description = "上传人")
    private String createBy;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "本次上传是否命中 md5 去重")
    private boolean duplicated;

    public static KnowledgeDocVO from(AssistantKnowledgeDoc doc) {
        KnowledgeDocVO vo = new KnowledgeDocVO();
        vo.setDocId(doc.getDocId());
        vo.setDocName(doc.getDocName());
        vo.setFileSuffix(doc.getFileSuffix());
        vo.setFileSize(doc.getFileSize());
        vo.setChunkStrategy(doc.getChunkStrategy());
        vo.setCharCount(doc.getCharCount());
        vo.setChunkCount(doc.getChunkCount());
        vo.setStatus(doc.getStatus());
        vo.setErrorMsg(doc.getErrorMsg());
        vo.setEnabled(doc.getEnabled() != null && doc.getEnabled() == 1);
        vo.setVectorIndexed(doc.getVectorIndexed() != null && doc.getVectorIndexed() == 1);
        vo.setCreateBy(doc.getCreateBy());
        vo.setCreateTime(doc.getCreateTime());
        vo.setUpdateTime(doc.getUpdateTime());
        return vo;
    }
}
