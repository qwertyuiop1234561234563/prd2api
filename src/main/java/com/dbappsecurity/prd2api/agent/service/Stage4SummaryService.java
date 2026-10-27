package com.dbappsecurity.prd2api.agent.service;

import com.dbappsecurity.prd2api.agent.dto.AgentContext;
import com.dbappsecurity.prd2api.constant.AgentPromptConstant;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 阶段4：汇总格式化 —— 输出固定 4 章节标准 Markdown 评审报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Stage4SummaryService {

    private final LlmInvokeService llmInvokeService;

    public String execute(AgentContext context) {
        log.info("阶段4 汇总格式化开始 taskId={}", context.getTaskId());
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append(AgentPromptConstant.STAGE4_SUMMARY_PROMPT)
                .append("\n【阶段1 业务理解】\n").append(context.getStage1Output())
                .append("\n\n【阶段2 待确认清单】\n").append(context.getStage2Output())
                .append("\n\n【阶段3 结构生成】\n").append(context.getStage3Output())
                .append("\n\n要求：输出的 Markdown 只保留四个章节，不要输出额外说明。");
        return llmInvokeService.chat(AgentPromptConstant.GLOBAL_SYSTEM_PROMPT,
                userPrompt.toString(), context.getModelCode());
    }
}
