package com.dbappsecurity.prd2api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.dto.AssistantChatDTO;
import com.dbappsecurity.prd2api.vo.AssistantMessageVO;
import com.dbappsecurity.prd2api.vo.AssistantReplyVO;
import com.dbappsecurity.prd2api.vo.AssistantSessionVO;

import java.util.List;

/**
 * 桌面助手服务：多轮问答 + 快捷操作 + 会话管理
 */
public interface AssistantService {

    /**
     * 提问：识别意图，能执行的操作直接查/执行，其余交给大模型或内置知识库
     */
    AssistantReplyVO chat(AssistantChatDTO dto);

    /**
     * 会话历史，按时间正序
     */
    List<AssistantMessageVO> history(String sessionId, int limit);

    /**
     * 我的会话分页，按最近活跃倒序
     */
    Page<AssistantSessionVO> pageSessions(int pageNum, int pageSize);

    /**
     * 删除会话（含全部消息）
     */
    void deleteSession(String sessionId);

    /**
     * 快捷提问，供弹窗初始展示
     */
    List<String> suggestions();

    /**
     * 定时任务：清理过期会话与消息，返回处理条数
     */
    int cleanExpired();
}
