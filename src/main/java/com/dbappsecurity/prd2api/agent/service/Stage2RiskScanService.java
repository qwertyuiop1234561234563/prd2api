package com.dbappsecurity.prd2api.agent.service;

import com.dbappsecurity.prd2api.agent.dto.AgentContext;
import com.dbappsecurity.prd2api.constant.AgentPromptConstant;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 阶段2：需求风险扫描 —— 字段/CRUD/分页/权限/异常/返回结构，输出待确认清单
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Stage2RiskScanService {

    private final LlmInvokeService llmInvokeService;

    public String execute(AgentContext context) {
        log.info("阶段2 风险扫描开始 taskId={}", context.getTaskId());
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append(AgentPromptConstant.STAGE2_RISK_SCAN_PROMPT)
                .append("\n【阶段1 业务理解结果】\n").append(context.getStage1Output())
                .append("\n\n【原文片段（RAG召回）】\n").append(context.chunkContext());
        return llmInvokeService.chat(AgentPromptConstant.GLOBAL_SYSTEM_PROMPT,
                userPrompt.toString(), context.getModelCode());
    }
}
