package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 助手知识库分段
 * <p>
 * 分段正文落库既支撑向量库不可用时的本地降级召回，也方便用户预览与核对引用来源。
 */
@Data
@TableName("assistant_knowledge_chunk")
public class AssistantKnowledgeChunk {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String docId;

    private String tenantId;

    /** 文档内序号，从 1 开始 */
    private Integer chunkNo;

    private String content;

    private Integer charCount;

    /** 引用来源标签，如《周报模板》#3 */
    private String source;

    /** 写入向量库的 ID，便于精确删除 */
    private String vectorId;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
