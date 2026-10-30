package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.entity.AssistantChatMessage;
import com.dbappsecurity.prd2api.service.AssistantMemoryService.MemoryItem;
import com.dbappsecurity.prd2api.vo.AgentStepVO;

import java.util.List;

/**
 * 桌面助手 Agent 编排
 * <p>
 * 优先走「大模型 + 工具调用」的 ReAct 循环；大模型不可用或调用失败时，
 * 自动降级到规则通道与本地知识库，保证对话与系统操作始终可用。
 */
public interface AssistantAgentService {

    /**
     * 执行一轮对话
     *
     * @param sessionId 会话 ID（危险操作确认票据绑定该会话）
     * @param message   用户消息（已做敏感词掩码）
     * @param history   最近若干条会话消息，按时间正序
     */
    AgentOutcome run(String sessionId, String message, List<AssistantChatMessage> history);

    /**
     * 执行一轮对话，并通过 listener 实时推送思考阶段与工具步骤（SSE 用）
     */
    AgentOutcome run(String sessionId, String message, List<AssistantChatMessage> history,
                     AgentListener listener);

    /**
     * Agent 过程回调，默认空实现
     */
    interface AgentListener {
        /** 进入第 N 步决策，模型正在思考 */
        default void onPhase(int stepNo, String phase) {
        }

        /** 单个工具执行完成（或等待确认） */
        default void onStep(AgentStepVO step) {
        }
    }

    /**
     * Agent 执行结果
     *
     * @param reply      展示文本
     * @param mode       AGENT（模型工具循环）/ RULE（规则通道）/ FAQ（知识库兜底）
     * @param degraded   是否未使用大模型
     * @param steps      轨迹
     * @param actionType 前端动作类型
     * @param actionData 动作数据
     * @param tokenUsed  本轮 token 消耗
     * @param memorySaved 本轮新增或更新的长期记忆
     */
    record AgentOutcome(String reply, String mode, boolean degraded, List<AgentStepVO> steps,
                        String actionType, String actionLabel, String actionTarget, Object actionData,
                        long tokenUsed, List<MemoryItem> memorySaved) {
    }
}
