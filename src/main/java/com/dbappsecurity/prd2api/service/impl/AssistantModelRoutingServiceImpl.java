package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.LlmModelEnum;
import com.dbappsecurity.prd2api.service.AssistantModelRoutingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型路由实现：确定性打分，零 token
 * <p>
 * 分数构成（上限 100）：
 * 基础 10 分；长度 30/80/200 字分别 +5/+10/+10；
 * 每个子句（，；？）加 4 分，最多 12 分；
 * 命中复合指令（并且/同时/分别/对比/先…再）加 12 分；
 * 命中推理诉求（为什么/分析/评估/方案）加 8 分；
 * 同时涉及 2 个及以上业务对象（任务/文件/额度/导出/取消/知识库）加 8 分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantModelRoutingServiceImpl implements AssistantModelRoutingService {

    private static final int SCORE_CAP = 100;
    private static final int CLAUSE_UNIT = 4;
    private static final int CLAUSE_CAP = 12;
    private static final List<String> COMPOUND_WORDS = List.of(
            "并且", "同时", "分别", "对比", "比较", "首先", "然后", "最后", "再", "以及");
    private static final List<String> REASONING_WORDS = List.of(
            "为什么", "分析", "评估", "推理", "原因", "方案", "建议", "设计");
    private static final List<String> TOOL_WORDS = List.of(
            "任务", "文件", "额度", "导出", "取消", "重试", "知识库", "报告");

    private final Prd2ApiProperties properties;

    @Override
    public Route route(String message) {
        String text = StrUtil.blankToDefault(message, "").trim();
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isRouteEnabled()) {
            return new Route("STANDARD", 0, "default", List.of("模型路由已关闭，使用默认模型"));
        }
        List<String> factors = new ArrayList<>();
        int score = 10;
        int length = StrUtil.length(text);
        if (length >= 30) {
            score += 5;
            factors.add("长度 " + length + " 字（+5）");
        }
        if (length >= 80) {
            score += 10;
            factors.add("长问题（+10）");
        }
        if (length >= 200) {
            score += 10;
            factors.add("超长问题（+10）");
        }
        int clauses = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '，' || c == '；' || c == '？' || c == '?') {
                clauses++;
            }
        }
        int clauseScore = Math.min(CLAUSE_CAP, clauses * CLAUSE_UNIT);
        if (clauseScore > 0) {
            score += clauseScore;
            factors.add("子句 " + clauses + " 个（+" + clauseScore + "）");
        }
        Set<String> compound = hits(text, COMPOUND_WORDS);
        if (!compound.isEmpty()) {
            score += 12;
            factors.add("复合指令 " + compound + "（+12）");
        }
        Set<String> reasoning = hits(text, REASONING_WORDS);
        if (!reasoning.isEmpty()) {
            score += 8;
            factors.add("推理诉求 " + reasoning + "（+8）");
        }
        Set<String> tools = hits(text, TOOL_WORDS);
        if (tools.size() >= 2) {
            score += 8;
            factors.add("涉及 " + tools.size() + " 个业务对象（+8）");
        }
        score = Math.min(SCORE_CAP, score);

        int simpleThreshold = Math.max(1, config.getRouteSimpleThreshold());
        int complexThreshold = Math.max(simpleThreshold + 1, config.getRouteComplexThreshold());
        String level;
        String modelCode;
        if (score < simpleThreshold) {
            level = "SIMPLE";
            modelCode = config.getRouteSimpleModel();
        } else if (score < complexThreshold) {
            level = "STANDARD";
            modelCode = config.getRouteStandardModel();
        } else {
            level = "COMPLEX";
            modelCode = config.getRouteComplexModel();
        }
        modelCode = safeModel(modelCode, factors);
        Route route = new Route(level, score, modelCode, factors);
        log.info("模型路由 level={} score={} model={}", level, score, modelCode);
        return route;
    }

    /**
     * 配置里写错模型编码时回落 default，避免一个配置笔误让整轮对话失败
     */
    private String safeModel(String modelCode, List<String> factors) {
        String code = StrUtil.blankToDefault(modelCode, "default");
        try {
            LlmModelEnum.resolve(code);
            return code;
        } catch (Exception e) {
            factors.add("模型编码 " + code + " 未注册，回落 default");
            return "default";
        }
    }

    private Set<String> hits(String text, List<String> words) {
        Set<String> hits = new LinkedHashSet<>();
        for (String word : words) {
            if (text.contains(word)) {
                hits.add(word);
            }
        }
        return hits;
    }
}
