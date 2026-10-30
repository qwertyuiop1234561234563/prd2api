package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.service.AssistantSelfCheckService;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import com.dbappsecurity.prd2api.vo.AgentStepVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接地自检实现：确定性规则优先，不额外调用模型
 * <p>
 * 三类校验：
 * 1）知识型答复必须标注来源（《文档》或“来源/知识库”）；
 * 2）答复中的长数字必须能在工具结果里找到，防止编造额度/任务数；
 * 3）答复与工具结果必须存在可核对交集，且不得声称完成了未执行过的写操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantSelfCheckServiceImpl implements AssistantSelfCheckService {

    private static final int MAX_EVIDENCE_CHARS = 1500;
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern WRITE_CLAIM = Pattern.compile("(已取消|已重试|已删除|已提交|已重排队)");

    private static final String REPAIR_SYSTEM = """
            你是事实校对员。只允许使用给定的「工具证据」重写答复，不允许引入任何新数字、新来源或新结论。
            如果证据不足以回答，就如实说“目前查不到/无法确认”，并给出下一步建议。
            输出中文纯文本，不超过 160 字。
            """;

    private final LlmInvokeService llmInvokeService;

    @Override
    public Result check(String question, String reply, List<AgentStepVO> steps) {
        List<AgentStepVO> evidenceSteps = evidence(steps);
        if (StrUtil.isBlank(reply)) {
            return new Result(false, false, List.of("答复为空"));
        }
        if (evidenceSteps.isEmpty()) {
            return new Result(true, true, List.of());
        }
        String evidence = evidenceText(evidenceSteps);
        Set<String> evidenceNumbers = numbers(evidence);
        List<String> issues = new ArrayList<>();

        // 1）知识型答复必须可追溯来源
        boolean knowledge = evidenceSteps.stream().anyMatch(step -> "KNOWLEDGE".equals(step.getType()));
        if (knowledge && !reply.contains("《") && !reply.contains("来源") && !reply.contains("知识库")) {
            issues.add("知识型答复未标注来源");
        }

        // 2）长数字必须能在证据中找到，防止编造额度/数量
        Set<String> replyNumbers = numbers(reply);
        List<String> fabricated = new ArrayList<>();
        for (String number : replyNumbers) {
            if (number.length() < 3 || evidenceNumbers.contains(number)) {
                continue;
            }
            fabricated.add(number);
        }
        if (!fabricated.isEmpty()) {
            issues.add("答复包含工具结果中不存在的数值：" + String.join("、", fabricated));
        }

        // 3）答复与证据至少要有可核对交集
        boolean sharesNumber = replyNumbers.stream().anyMatch(evidenceNumbers::contains);
        if (!sharesNumber && !sharesBigram(reply, evidence)) {
            issues.add("答复与工具结果没有可核对的内容");
        }

        // 4）不得声称完成了未执行过的写操作
        boolean executedWrite = evidenceSteps.stream().anyMatch(step -> step.isSuccess()
                && ("cancel_task".equals(step.getToolName()) || "retry_task".equals(step.getToolName())));
        if (!executedWrite && WRITE_CLAIM.matcher(reply).find()) {
            issues.add("答复声称已执行写操作，但本轮没有成功的写工具调用");
        }
        return new Result(false, issues.isEmpty(), issues);
    }

    @Override
    public String repair(String question, String reply, List<AgentStepVO> steps, List<String> issues) {
        List<AgentStepVO> evidenceSteps = evidence(steps);
        if (evidenceSteps.isEmpty()) {
            return null;
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("【用户问题】").append(StrUtil.maxLength(question, 300)).append('\n');
        prompt.append("【原答复】").append(StrUtil.maxLength(reply, 600)).append('\n');
        prompt.append("【工具证据】\n").append(StrUtil.maxLength(evidenceText(evidenceSteps), MAX_EVIDENCE_CHARS))
                .append('\n');
        prompt.append("【发现问题】").append(String.join("；", issues)).append('\n');
        prompt.append("请基于证据重写答复。");
        try {
            String repaired = llmInvokeService.chat(REPAIR_SYSTEM, prompt.toString(), "default");
            return StrUtil.isBlank(repaired) ? null : repaired.trim();
        } catch (Exception e) {
            log.warn("接地自检重写失败 msg={}", e.getMessage());
            return null;
        }
    }

    /**
     * 可作为证据的步骤：成功执行且带文本结果的工具/知识检索
     */
    private List<AgentStepVO> evidence(List<AgentStepVO> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .filter(AgentStepVO::isSuccess)
                .filter(step -> "TOOL".equals(step.getType()) || "KNOWLEDGE".equals(step.getType()))
                .filter(step -> StrUtil.isNotBlank(step.getResult()))
                .toList();
    }

    private String evidenceText(List<AgentStepVO> steps) {
        StringBuilder text = new StringBuilder();
        for (AgentStepVO step : steps) {
            text.append("- ").append(StrUtil.blankToDefault(step.getTitle(), step.getToolName()))
                    .append("：").append(step.getResult()).append('\n');
        }
        return text.toString();
    }

    private Set<String> numbers(String text) {
        Set<String> result = new HashSet<>();
        if (StrUtil.isBlank(text)) {
            return result;
        }
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group().replace(",", "");
            if (StrUtil.isNotBlank(raw)) {
                result.add(raw);
            }
        }
        return result;
    }

    /**
     * 中文/英文 bigram 交集，用于判断答复是否真的引用了证据
     */
    private boolean sharesBigram(String a, String b) {
        String left = a.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
        String right = b.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
        if (left.length() < 2 || right.length() < 2) {
            return false;
        }
        for (int i = 0; i + 1 < left.length(); i++) {
            if (right.contains(left.substring(i, i + 2))) {
                return true;
            }
        }
        return false;
    }
}
