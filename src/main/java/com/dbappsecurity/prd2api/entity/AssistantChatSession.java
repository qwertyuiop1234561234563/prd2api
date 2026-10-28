package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 桌面助手会话
 */
@Data
@TableName("assistant_chat_session")
public class AssistantChatSession {

    @TableId(value = "session_id", type = IdType.INPUT)
    private String sessionId;

    private String tenantId;

    private String userId;

    /** 会话标题，取首条提问 */
    private String title;

    private Integer messageCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
