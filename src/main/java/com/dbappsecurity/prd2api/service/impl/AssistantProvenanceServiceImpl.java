package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.dbappsecurity.prd2api.service.AssistantProvenanceService;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.vo.AgentStepVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 答复溯源实现：把证据片段在答复里做原文对齐
 * <p>
 * 对齐策略：证据与答复都去掉空白后按长度递减的窗口滑动匹配，命中即认为答复确实引用了该证据，
 * 并记录原文中的字符区间。没有命中的证据记为“未引用”，用于计算引用覆盖率。
 * 纯字符串运算、零额外模型调用，结果稳定可回归。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantProvenanceServiceImpl implements AssistantProvenanceService {

    /** 可作为证据的轨迹类型 */
    private static final List<String> EVIDENCE_TYPES = List.of("KNOWLEDGE", "TOOL", "SUBAGENT", "MEMORY");

    /** 窗口长度从长到短：优先长片段对齐；最短 12 字，避免“的”“了”这类短词误命中 */
    private static final int[] WINDOWS = {24, 18, 14, 12};

    /** 证据文本过短（如“完成”）不参与覆盖率统计 */
    private static final int MIN_EVIDENCE_CHARS = 20;

    private final MetricUtil metricUtil;

    @Override
    public Report trace(String reply, List<AgentStepVO> steps) {
        if (StrUtil.isBlank(reply) || steps == null || steps.isEmpty()) {
            return new Report(0.0D, 0, 0, List.of());
        }
        String normReply = normalize(reply);
        if (normReply.isEmpty()) {
            return new Report(0.0D, 0, 0, List.of());
        }
        int[] map = originalIndexMap(reply);
        List<Anchor> anchors = new ArrayList<>();
        int total = 0;
        int verified = 0;
        for (AgentStepVO step : steps) {
            if (!step.isSuccess() || !EVIDENCE_TYPES.contains(StrUtil.blankToDefault(step.getType(), ""))) {
                continue;
            }
            String text = StrUtil.blankToDefault(step.getResult(), "");
            String normText = normalize(text);
            if (normText.length() < MIN_EVIDENCE_CHARS) {
                continue;
            }
            total++;
            String source = StrUtil.blankToDefault(step.getSource(), step.getTitle());
            String chunkId = DigestUtil.md5Hex(source + "|" + normText).substring(0, 8);
            Anchor anchor = locate(step, normReply, normText, map, source, chunkId);
            if (anchor.verified()) {
                verified++;
            }
            anchors.add(anchor);
        }
        double coverage = total == 0 ? 0.0D : (double) verified / total;
        metricUtil.agentProvenance(verified, total, coverage);
        return new Report(coverage, verified, total, anchors);
    }

    /**
     * 在答复中定位证据片段：按窗口长度递减匹配第一段可用原文
     */
    private Anchor locate(AgentStepVO step, String normReply, String normText, int[] map,
                          String source, String chunkId) {
        for (int len : WINDOWS) {
            if (len > normText.length()) {
                continue;
            }
            for (int i = 0; i + len <= normText.length(); i++) {
                String piece = normText.substring(i, i + len);
                int hit = normReply.indexOf(piece);
                if (hit >= 0) {
                    int start = map[hit];
                    int end = map[hit + len - 1] + 1;
                    double overlap = (double) len / normText.length();
                    return new Anchor(step.getStepNo(), step.getType(), source, chunkId,
                            start, end, overlap);
                }
            }
        }
        return new Anchor(step.getStepNo(), step.getType(), source, chunkId, -1, -1, 0.0D);
    }

    /**
     * 归一化文本下标 → 原文下标，保证返回给前端的区间可以直接高亮
     */
    private int[] originalIndexMap(String original) {
        int[] map = new int[normalize(original).length()];
        int cursor = 0;
        for (int i = 0; i < original.length(); i++) {
            if (!Character.isWhitespace(original.charAt(i))) {
                map[cursor++] = i;
            }
        }
        return map;
    }

    /**
     * 去空白归一化，与下标映射使用同一判定，保证长度严格一致
     */
    private String normalize(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch)) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }
}
