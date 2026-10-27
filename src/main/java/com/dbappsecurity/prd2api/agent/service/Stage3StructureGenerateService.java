package com.dbappsecurity.prd2api.agent.service;

import com.dbappsecurity.prd2api.agent.dto.AgentContext;
import com.dbappsecurity.prd2api.constant.AgentPromptConstant;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 阶段3：结构生成 —— MySQL DDL、REST 接口清单、研发细分任务清单
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Stage3StructureGenerateService {

    private final LlmInvokeService llmInvokeService;

    public String execute(AgentContext context) {
        log.info("阶段3 结构生成开始 taskId={}", context.getTaskId());
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append(AgentPromptConstant.STAGE3_STRUCTURE_GENERATE_PROMPT)
                .append("\n【阶段1 业务理解】\n").append(context.getStage1Output())
                .append("\n\n【阶段2 待确认清单】\n").append(context.getStage2Output())
                .append("\n\n【原文片段（RAG召回）】\n").append(context.chunkContext());
        return llmInvokeService.chat(AgentPromptConstant.GLOBAL_SYSTEM_PROMPT,
                userPrompt.toString(), context.getModelCode());
    }
}
