package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 助手知识库文档
 * <p>
 * 租户级离线知识：上传后异步解析、切分、向量化，完成后进入 Agent 检索范围。
 */
@Data
@TableName("assistant_knowledge_doc")
public class AssistantKnowledgeDoc {

    @TableId(value = "doc_id", type = IdType.ASSIGN_UUID)
    private String docId;

    private String tenantId;

    /** 展示用文件名（已清洗） */
    private String docName;

    private String fileSuffix;

    private String fileStorePath;

    private String fileMd5;

    private Long fileSize;

    /** 切分策略：fixed / semantic */
    private String chunkStrategy;

    /** 正文有效字符数 */
    private Integer charCount;

    /** 分块数 */
    private Integer chunkCount;

    /** 见 KnowledgeDocStatus */
    private String status;

    /** 失败原因，截断存储 */
    private String errorMsg;

    /** 是否参与检索 0 停用 1 启用 */
    private Integer enabled;

    /** 是否已写入向量库 0 否 1 是；向量不可用时为 0，仍可本地召回 */
    private Integer vectorIndexed;

    /** 上传人：登录用户ID，Header 模式回退为 userKey */
    private String createBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
