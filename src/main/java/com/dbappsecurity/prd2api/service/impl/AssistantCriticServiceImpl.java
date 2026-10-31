package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.service.AssistantCriticService;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import com.dbappsecurity.prd2api.vo.AgentStepVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Actor-Critic 实现：LLM-as-judge + 单次重写
 * <p>
 * 评审只读本轮工具证据，不接触其他上下文；输出严格 JSON，解析失败按“跳过”处理，
 * 保证评审器异常不会影响主回答，也不会导致整轮对话失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantCriticServiceImpl implements AssistantCriticService {

    private static final int MAX_EVIDENCE_CHARS = 1200;
    private static final int MAX_REPLY_CHARS = 800;

    private static final String CRITIC_SYSTEM = """
            你是严格但简洁的答复评审员。只依据给定的「工具证据」评审答复，不要引入外部知识。
            按三个维度打 1~5 分：
            - faithfulness：答复中的事实/数字是否都能在证据中找到，是否有编造（5=完全有据）；
            - completeness：是否回答了用户问题的关键点，必要时是否给出下一步（5=完整）；
            - actionability：用户能否据此直接行动，是否给出明确结论或可点击动作（5=可直接执行）。
            只输出 JSON，禁止输出解释或 Markdown 代码块：
            {"faithfulness":1-5,"completeness":1-5,"actionability":1-5,"issues":["最多3条具体问题"]}
            如果答复只是寒暄、或证据不足以回答而答复已如实说明，各维度给 4 分，issues 留空。
            """;

    private static final String REWRITE_SYSTEM = """
            你是修订员。只允许使用给定的「工具证据」和「评审意见」重写答复，不得引入新数字、新来源、新结论。
            要求：直接回答用户问题；知识型内容标注来源；证据不足时如实说明并给出下一步建议；
            输出中文纯文本，不超过 180 字。
            """;

    private final LlmInvokeService llmInvokeService;
    private final ObjectMapper objectMapper;
    private final Prd2ApiProperties properties;

    @Override
    public Review review(String question, String reply, List<AgentStepVO> steps) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isCriticEnabled() || StrUtil.isBlank(reply) || !llmInvokeService.available()) {
            return Review.skipped();
        }
        String evidence = evidenceText(steps);
        if (StrUtil.isBlank(evidence)) {
            // 没有可核对证据（寒暄/规则答复）时不评审，避免评审器凭空打分
            return Review.skipped();
        }
        StringBuilder prompt = new StringBuilder("【用户问题】").append(StrUtil.maxLength(question, 300)).append('\n');
        prompt.append("【答复】").append(StrUtil.maxLength(reply, MAX_REPLY_CHARS)).append('\n');
        prompt.append("【工具证据】\n").append(StrUtil.maxLength(evidence, MAX_EVIDENCE_CHARS)).append('\n');
        prompt.append("请评审并输出 JSON。");
        try {
            String raw = llmInvokeService.chat(CRITIC_SYSTEM, prompt.toString(), "default");
            Review review = parse(raw, Math.max(1, Math.min(5, config.getCriticMinScore())));
            if (review == null) {
                log.info("评审输出无法解析，跳过本轮评审 raw={}", StrUtil.maxLength(raw, 100));
                return Review.skipped();
            }
            return review;
        } catch (Exception e) {
            log.warn("评审调用失败，跳过本轮评审 msg={}", e.getMessage());
            return Review.skipped();
        }
    }

    @Override
    public Rewrite rewrite(String question, String reply, List<AgentStepVO> steps, Review review) {
        if (review == null || !review.ran() || review.issues().isEmpty()) {
            return null;
        }
        String evidence = evidenceText(steps);
        if (StrUtil.isBlank(evidence)) {
            return null;
        }
        StringBuilder prompt = new StringBuilder("【用户问题】").append(StrUtil.maxLength(question, 300)).append('\n');
        prompt.append("【原答复】").append(StrUtil.maxLength(reply, MAX_REPLY_CHARS)).append('\n');
        prompt.append("【工具证据】\n").append(StrUtil.maxLength(evidence, MAX_EVIDENCE_CHARS)).append('\n');
        prompt.append("【评审意见】").append(String.join("；", review.issues())).append('\n');
        prompt.append("请重写答复。");
        try {
            String rewritten = llmInvokeService.chat(REWRITE_SYSTEM, prompt.toString(), "default");
            return StrUtil.isBlank(rewritten) ? null : new Rewrite(rewritten.trim(), 0L);
        } catch (Exception e) {
            log.warn("评审重写失败，保留原答复 msg={}", e.getMessage());
            return null;
        }
    }

    /**
     * 宽容解析评审 JSON：允许代码块与前后夹带文字
     */
    private Review parse(String raw, int minScore) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(raw.substring(start, end + 1));
            int faithfulness = clamp(root.path("faithfulness").asInt(0));
            int completeness = clamp(root.path("completeness").asInt(0));
            int actionability = clamp(root.path("actionability").asInt(0));
            if (faithfulness == 0 || completeness == 0 || actionability == 0) {
                return null;
            }
            List<String> issues = new ArrayList<>();
            JsonNode array = root.path("issues");
            if (array.isArray()) {
                for (JsonNode node : array) {
                    if (issues.size() >= 3) {
                        break;
                    }
                    String issue = node.asText("").trim();
                    if (StrUtil.isNotBlank(issue)) {
                        issues.add(StrUtil.maxLength(issue, 100));
                    }
                }
            }
            boolean passed = faithfulness >= minScore
                    && completeness >= Math.max(1, minScore - 1)
                    && actionability >= Math.max(1, minScore - 1);
            return new Review(true, faithfulness, completeness, actionability, passed, issues, 0L);
        } catch (Exception e) {
            return null;
        }
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(5, score));
    }

    /**
     * 评审证据：只看成功执行且带文本结果的工具/知识/子 Agent/记忆步骤
     */
    private String evidenceText(List<AgentStepVO> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (AgentStepVO step : steps) {
            if (!step.isSuccess() || StrUtil.isBlank(step.getResult())) {
                continue;
            }
            String type = StrUtil.blankToDefault(step.getType(), "");
            if (!"TOOL".equals(type) && !"KNOWLEDGE".equals(type)
                    && !"SUBAGENT".equals(type) && !"MEMORY".equals(type)) {
                continue;
            }
            text.append("- ").append(StrUtil.blankToDefault(step.getTitle(), step.getToolName()))
                    .append("：").append(step.getResult()).append('\n');
        }
        return text.toString().trim();
    }
}
