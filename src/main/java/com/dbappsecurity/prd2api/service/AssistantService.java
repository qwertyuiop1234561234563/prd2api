package com.dbappsecurity.prd2api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.dto.AssistantChatDTO;
import com.dbappsecurity.prd2api.vo.AgentStepVO;
import com.dbappsecurity.prd2api.vo.AssistantCapabilityVO;
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
     * 带步骤回调的提问，用于 SSE 流式推送「思考 → 调用工具 → 生成答复」
     */
    AssistantReplyVO chat(AssistantChatDTO dto, AssistantAgentService.AgentListener listener);

    /**
     * 确认并执行危险操作：消费一次性票据后才真正调用工具
     */
    AssistantReplyVO confirm(String confirmId);

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
     * 助手能力清单：模式、工具、记忆与知识库规模
     */
    AssistantCapabilityVO capabilities();

    /**
     * 会话内的 Agent 轨迹，按时间正序
     */
    List<AgentStepVO> trace(String sessionId, int limit);

    /**
     * 定时任务：清理过期会话与消息，返回处理条数
     */
    int cleanExpired();

    /**
     * 当前会话的互斥锁状态，用于前端提示“上一轮仍在处理”
     */
    LockState lockState(String sessionId);

    /**
     * 会话锁状态
     *
     * @param locked     是否被占用
     * @param ttlSeconds 剩余有效期（秒），-1 表示未占用
     */
    record LockState(boolean locked, long ttlSeconds) {
    }
}
