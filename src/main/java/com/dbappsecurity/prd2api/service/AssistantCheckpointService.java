package com.dbappsecurity.prd2api.service;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.vo.AgentStepVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 断点续跑（Checkpoint & Resume）
 * <p>
 * 长任务被用户中断、预算熔断、等待确认或工具失败时，把“目标 + 已完成步骤”存成检查点；
 * 用户下一轮说“继续”时恢复检查点，已完成的工具调用直接复用结果，不再重复执行。
 */
public interface AssistantCheckpointService {

    /**
     * 保存检查点
     *
     * @param sessionId 会话 ID
     * @param goal      本轮用户目标
     * @param done      已完成步骤（可复用结果）
     * @param stepCount 本轮轨迹步数
     * @param reason    暂停原因
     */
    void save(String sessionId, String goal, List<DoneStep> done, int stepCount, String reason);

    /**
     * 读取当前会话的检查点，没有则返回 null
     */
    Snapshot load(String sessionId);

    /**
     * 清除检查点（任务已完成）
     */
    void clear(String sessionId);

    /**
     * 用户这句话是否表示“继续上一轮未完成的任务”
     */
    boolean resumeIntent(String message);

    /**
     * 合并历史已完成步骤与本轮轨迹，按工具签名去重并限制条数
     */
    List<DoneStep> merge(List<DoneStep> previous, List<AgentStepVO> steps);

    /**
     * 工具调用签名：工具名 + 归一化参数，用于判断“这一步是否已经做过”
     */
    static String signature(String toolName, String arguments) {
        return StrUtil.blankToDefault(toolName, "") + "|"
                + StrUtil.maxLength(StrUtil.blankToDefault(arguments, "{}"), 160).replaceAll("\\s+", "");
    }

    /**
     * 已完成步骤
     */
    record DoneStep(String toolName, String arguments, String signature, String summary, String source) {
    }

    /**
     * 检查点快照
     */
    record Snapshot(String goal, String reason, List<DoneStep> done, int stepCount, LocalDateTime at) {
    }
}
