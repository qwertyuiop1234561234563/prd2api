package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.service.AssistantGoalDriftService;
import com.dbappsecurity.prd2api.vo.AgentStepVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 目标漂移检测实现：关键词命中率 + 步骤类型过滤
 * <p>
 * 判定口径：
 * 1. 只统计会产生事实/副作用的步骤（TOOL/KNOWLEDGE/MEMORY/SUBAGENT），忽略路由、计划、模型等元步骤；
 * 2. 目标关键词优先取业务词典，目标里没有业务词时退化为中文字符二元组；
 * 3. 步骤文本命中任一目标关键词即视为“围绕目标”；
 * 4. 聚焦比例低于阈值判为漂移，介于弱相关与阈值之间判为弱相关。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantGoalDriftServiceImpl implements AssistantGoalDriftService {

    private static final Set<String> CONSIDERED_TYPES = Set.of("TOOL", "KNOWLEDGE", "MEMORY", "SUBAGENT");
    /** 业务对象词典：覆盖当前工具能触达的领域，避免“查询/我的”这类泛词造成误判 */
    private static final List<String> BUSINESS_TERMS = List.of(
            "任务", "文件", "额度", "会员", "知识库", "导出", "取消", "重试", "报告", "记忆",
            "数据", "页面", "专家", "统计", "优惠券", "订单", "下载", "上传", "进度", "状态");
    private static final Set<String> STOP_GRAMS = Set.of(
            "我的", "帮我", "一下", "可以", "请问", "这个", "那个", "然后", "现在", "需要",
            "进行", "如何", "什么", "怎么", "是否", "多少", "以及", "还有", "相关");
    private static final int MAX_OFF_TOPIC = 5;
    private static final int MAX_FALLBACK_TERMS = 20;

    private final Prd2ApiProperties properties;

    @Override
    public Assessment assess(String goal, List<AgentStepVO> steps) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        int minSteps = config == null ? 3 : Math.max(1, config.getGoalDriftMinSteps());
        double focusThreshold = config == null ? 0.6D : Math.max(0.1D, config.getGoalDriftFocusThreshold());
        double weakThreshold = config == null ? 0.35D : Math.max(0.05D, config.getGoalDriftWeakThreshold());
        if (config == null || !config.isGoalDriftEnabled()) {
            return new Assessment("INSUFFICIENT", 0.0D, 0, 0, List.of(), "目标漂移检测已关闭，未做判定");
        }
        if (StrUtil.isBlank(goal)) {
            return new Assessment("INSUFFICIENT", 0.0D, 0, 0, List.of(), "缺少目标，无法判断执行路径是否漂移");
        }
        Set<String> terms = goalTerms(goal);
        if (terms.isEmpty()) {
            return new Assessment("INSUFFICIENT", 0.0D, 0, 0, List.of(),
                    "目标中缺少可识别的业务关键词，建议补充明确对象");
        }
        List<AgentStepVO> considered = new ArrayList<>();
        if (steps != null) {
            for (AgentStepVO step : steps) {
                if (step != null && CONSIDERED_TYPES.contains(StrUtil.blankToDefault(step.getType(), ""))) {
                    considered.add(step);
                }
            }
        }
        if (considered.size() < minSteps) {
            return new Assessment("INSUFFICIENT", 0.0D, considered.size(), 0, List.of(),
                    "已执行步骤不足 " + minSteps + " 步，暂不判定漂移");
        }
        int onTopic = 0;
        List<String> offTopic = new ArrayList<>();
        for (AgentStepVO step : considered) {
            if (containsAny(stepText(step), terms)) {
                onTopic++;
            } else if (offTopic.size() < MAX_OFF_TOPIC) {
                offTopic.add(StrUtil.blankToDefault(step.getToolName(), step.getType()));
            }
        }
        double ratio = round((double) onTopic / considered.size());
        String verdict;
        String suggestion;
        if (ratio >= focusThreshold) {
            verdict = "FOCUSED";
            suggestion = "执行路径与目标一致，可继续推进";
        } else if (ratio >= weakThreshold) {
            verdict = "WEAK";
            suggestion = "部分步骤与目标关联较弱，建议核对是否必要后收敛";
        } else {
            verdict = "DRIFTED";
            suggestion = "已偏离初始目标，建议停止无关操作并回到目标；无法推进时直接说明缺口";
        }
        log.info("目标漂移评估 verdict={} focus={} steps={}/{} goal={}",
                verdict, ratio, onTopic, considered.size(), StrUtil.maxLength(goal, 40));
        return new Assessment(verdict, ratio, considered.size(), onTopic, offTopic, suggestion);
    }

    /**
     * 目标关键词：优先业务词典，其次中文二元组（过滤泛词），保证短目标也能评估
     */
    private Set<String> goalTerms(String goal) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : BUSINESS_TERMS) {
            if (goal.contains(term)) {
                terms.add(term);
            }
        }
        if (!terms.isEmpty()) {
            return terms;
        }
        String normalized = goal.replaceAll("[\\s，。；：、！？,.!?;:（）()\\[\\]【】\"'“”‘’]", "");
        for (int i = 0; i + 1 < normalized.length() && terms.size() < MAX_FALLBACK_TERMS; i++) {
            String gram = normalized.substring(i, i + 2);
            if (gram.chars().allMatch(Character::isLetterOrDigit) && !STOP_GRAMS.contains(gram)) {
                terms.add(gram);
            }
        }
        return terms;
    }

    private boolean containsAny(String text, Set<String> terms) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String stepText(AgentStepVO step) {
        return StrUtil.blankToDefault(step.getType(), "") + " "
                + StrUtil.blankToDefault(step.getToolName(), "") + " "
                + StrUtil.blankToDefault(step.getTitle(), "") + " "
                + StrUtil.blankToDefault(step.getArguments(), "") + " "
                + StrUtil.blankToDefault(step.getResult(), "");
    }

    private double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
