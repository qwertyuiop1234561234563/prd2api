package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 桌面助手消息明细
 */
@Data
@TableName("assistant_chat_message")
public class AssistantChatMessage {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String tenantId;

    private String userId;

    /** USER / ASSISTANT */
    private String role;

    private String content;

    /** 助手回复命中的意图编码，用户消息为空 */
    private String intent;

    private LocalDateTime createTime;
}
