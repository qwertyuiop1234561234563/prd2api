package com.dbappsecurity.prd2api.agent.service;

import com.dbappsecurity.prd2api.agent.dto.AgentContext;
import com.dbappsecurity.prd2api.constant.AgentPromptConstant;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 阶段1：业务理解 —— 输出业务目标与业务实体，禁止设计表/接口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Stage1BusinessUnderstandService {

    private final LlmInvokeService llmInvokeService;

    public String execute(AgentContext context) {
        log.info("阶段1 业务理解开始 taskId={}", context.getTaskId());
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append(AgentPromptConstant.STAGE1_BUSINESS_UNDERSTAND_PROMPT)
                .append("\n【原文开始】\n").append(context.getDocText()).append("\n【原文结束】\n");
        if (context.isDocCompressed()) {
            userPrompt.append("\n注意：原文过长已做首尾保留压缩，被省略部分如涉及业务实体请标注【❓待确认，文档未定义】。\n");
        }
        userPrompt.append("\n【RAG召回片段】\n").append(context.chunkContext());
        return llmInvokeService.chat(AgentPromptConstant.GLOBAL_SYSTEM_PROMPT,
                userPrompt.toString(), context.getModelCode());
    }
}
