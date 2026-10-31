package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.agent.tool.AssistantToolRegistry.ToolResult;
import com.dbappsecurity.prd2api.service.LlmInvokeService.ToolCall;

import java.util.List;

/**
 * 工具输出契约服务
 * <p>
 * 解决的问题：工具返回 success=true 并不代表结果可用（空检索、字段缺失、原始 JSON 回灌都会让模型误判）。
 * 这里对工具结果做确定性契约校验，并给出结构化的修复动作（改写参数重检 / 降级为失败并注入诚实提示）。
 */
public interface AssistantToolContractService {

    /**
     * 校验工具输出；valid=false 时携带修复方案
     */
    Check check(String toolName, ToolResult result, String arguments);

    /**
     * 无法修复时把“伪成功”降级为如实失败，避免模型基于坏数据编造结论
     */
    ToolResult degrade(String toolName, ToolResult original, List<Violation> violations);

    /**
     * 契约违规项
     */
    record Violation(String code, String detail) {
    }

    /**
     * 校验结果：repairCall 非空表示可自动重试一次；degradeToFailure 表示修复失败后必须降级
     */
    record Check(boolean valid, List<Violation> violations, ToolCall repairCall,
                 boolean degradeToFailure, String repairNote) {
    }
}
