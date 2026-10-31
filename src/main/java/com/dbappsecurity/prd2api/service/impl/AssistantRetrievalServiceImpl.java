package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.rag.KnowledgeBaseService;
import com.dbappsecurity.prd2api.service.AssistantRetrievalService;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.SimilarityUtil;
import com.dbappsecurity.prd2api.vo.RagChunkVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Corrective RAG 实现：首跳召回 → 置信度评估 → 查询改写补检 → 证据充足性判定
 * <p>
 * 关键取舍：只有首跳置信度不足时才多花一次模型调用改写查询，命中置信度足够的问题保持零额外成本；
 * 模型不可用或改写失败时退化为规则去噪查询，保证检索链路始终可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantRetrievalServiceImpl implements AssistantRetrievalService {

    private static final int MAX_QUERY_CHARS = 120;
    private static final int MAX_NOTE_CHARS = 200;
    private static final int MAX_SUB_QUERY_CHARS = 30;

    /** 口语化噪声，离线兜底改写时剔除 */
    private static final Pattern NOISE = Pattern.compile(
            "(请问|帮我|麻烦|我想知道|想知道|告诉我|解释一下|说明一下|那个|这个|这套|一套|一下|怎么|如何)");
    private static final Pattern PUNCTUATION = Pattern.compile("[？?。！!，,；;：:\\s]+");

    private static final String REWRITE_SYSTEM = """
            你是检索查询改写器。把用户问题改写成 1~2 条更适合知识库检索的短查询：
            - 保留业务名词与关键限定词，去掉口语、寒暄与代词；
            - 每条不超过 20 个汉字，角度要彼此不同（例如概念名与同义说法）；
            - 不要输出原问题，不要编造平台不存在的功能。
            只输出 JSON，禁止解释或 Markdown 代码块：{"queries":["查询1","查询2"]}
            """;

    private final KnowledgeBaseService knowledgeBaseService;
    private final LlmInvokeService llmInvokeService;
    private final Prd2ApiProperties properties;
    private final ObjectMapper objectMapper;
    private final MetricUtil metricUtil;

    @Override
    public Retrieval retrieve(String query, int topK) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (StrUtil.isBlank(query)) {
            metricUtil.agentRetrieval(false, 1, 0.0D, false);
            return Retrieval.empty();
        }
        int k = topK > 0 ? topK : Math.max(1, config == null ? 3 : config.getKnowledgeTopK());
        double threshold = config == null ? 0.45D : Math.max(0.05D, config.getRetrievalMinConfidence());
        int maxHops = config == null ? 2 : Math.max(1, config.getRetrievalMaxHops());
        boolean enabled = config != null && config.isCorrectiveRag() && llmInvokeService.available();

        List<RagChunkVO> merged = new ArrayList<>();
        List<String> executed = new ArrayList<>();
        executed.add(query);
        merge(merged, knowledgeBaseService.search(query, k));
        int hops = 1;
        double baseConfidence = confidence(merged, executed);
        boolean rewritten = false;
        int subQueries = 0;

        // 首跳置信度不足：改写查询补一跳
        if (enabled && hops < maxHops && (merged.isEmpty() || baseConfidence < threshold)) {
            List<String> subs = rewrite(query, config);
            for (String sub : subs) {
                executed.add(sub);
                merge(merged, knowledgeBaseService.search(sub, k));
            }
            subQueries = subs.size();
            if (subQueries > 0) {
                rewritten = true;
                hops++;
            }
        }
        // 仍不足且还有跳数预算：拿最佳片段的来源标题再做一跳
        if (enabled && hops < maxHops && confidence(merged, executed) < threshold && !merged.isEmpty()) {
            String title = StrUtil.blankToDefault(merged.get(0).getSource(), "");
            if (StrUtil.isNotBlank(title) && !title.equals(query)) {
                executed.add(title);
                merge(merged, knowledgeBaseService.search(title, k));
                hops++;
            }
        }

        List<RagChunkVO> top = merged.stream()
                .sorted(Comparator.comparingDouble(RagChunkVO::getScore).reversed())
                .limit(k)
                .toList();
        double finalConfidence = confidence(top, executed);
        boolean sufficient = !top.isEmpty() && finalConfidence >= threshold;
        String note = note(executed, subQueries, hops, baseConfidence, finalConfidence, sufficient, threshold);
        metricUtil.agentRetrieval(rewritten, hops, finalConfidence, sufficient);
        log.info("知识检索 query={} hops={} rewritten={} confidence={}->{} sufficient={}",
                StrUtil.maxLength(query, 40), hops, rewritten,
                String.format("%.2f", baseConfidence), String.format("%.2f", finalConfidence), sufficient);
        return new Retrieval(top, executed, hops, rewritten, baseConfidence, finalConfidence, sufficient, note);
    }

    /**
     * 查询改写：模型优先，失败或不可用时用去噪规则兜底
     */
    private List<String> rewrite(String query, Prd2ApiProperties.Agent config) {
        int limit = Math.max(1, config.getRetrievalRewriteLimit());
        try {
            String raw = llmInvokeService.chat(REWRITE_SYSTEM,
                    "【用户问题】" + StrUtil.maxLength(query, MAX_QUERY_CHARS), "default");
            List<String> parsed = parseQueries(raw, limit, query);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        } catch (Exception e) {
            log.warn("查询改写失败，使用规则兜底 msg={}", e.getMessage());
        }
        return deterministicRewrite(query);
    }

    /**
     * 宽容解析改写 JSON：允许代码块、对象包裹或直接数组
     */
    private List<String> parseQueries(String raw, int limit, String origin) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        int arrayStart = raw.indexOf('[');
        int arrayEnd = raw.lastIndexOf(']');
        int objectStart = raw.indexOf('{');
        int objectEnd = raw.lastIndexOf('}');
        try {
            JsonNode node;
            if (arrayStart >= 0 && arrayEnd > arrayStart) {
                node = objectMapper.readTree(raw.substring(arrayStart, arrayEnd + 1));
            } else if (objectStart >= 0 && objectEnd > objectStart) {
                node = objectMapper.readTree(raw.substring(objectStart, objectEnd + 1)).path("queries");
            } else {
                return List.of();
            }
            JsonNode queries = node.isArray() ? node : node.path("queries");
            if (!queries.isArray()) {
                return List.of();
            }
            Set<String> unique = new LinkedHashSet<>();
            for (JsonNode item : queries) {
                String text = StrUtil.maxLength(item.asText("").trim(), MAX_SUB_QUERY_CHARS);
                if (StrUtil.isNotBlank(text) && !text.equals(origin) && unique.size() < limit) {
                    unique.add(text);
                }
            }
            return new ArrayList<>(unique);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 规则兜底改写：剔除口语噪声后保留业务名词
     */
    private List<String> deterministicRewrite(String query) {
        String cleaned = PUNCTUATION.matcher(NOISE.matcher(query).replaceAll(" ")).replaceAll(" ")
                .trim();
        if (StrUtil.isBlank(cleaned)) {
            cleaned = query;
        }
        return List.of(StrUtil.maxLength(cleaned, MAX_SUB_QUERY_CHARS));
    }

    /**
     * 合并去重：同一片段保留更高分
     */
    private void merge(List<RagChunkVO> target, List<RagChunkVO> incoming) {
        if (incoming == null) {
            return;
        }
        for (RagChunkVO chunk : incoming) {
            if (chunk == null || StrUtil.isBlank(chunk.getContent())) {
                continue;
            }
            boolean exists = false;
            for (int i = 0; i < target.size(); i++) {
                if (target.get(i).getContent().equals(chunk.getContent())) {
                    if (chunk.getScore() > target.get(i).getScore()) {
                        target.set(i, chunk);
                    }
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                target.add(chunk);
            }
        }
    }

    /**
     * 检索置信度：向量分与查询词覆盖率取较大值，统一向量召回与本地降级召回的口径
     */
    private double confidence(List<RagChunkVO> chunks, List<String> queries) {
        if (chunks == null || chunks.isEmpty() || queries == null || queries.isEmpty()) {
            return 0.0D;
        }
        double best = 0.0D;
        for (RagChunkVO chunk : chunks) {
            best = Math.max(best, chunk.getScore());
            for (String query : queries) {
                best = Math.max(best, SimilarityUtil.coverage(query, chunk.getContent()));
            }
        }
        return Math.min(1.0D, best);
    }

    private String note(List<String> executed, int subQueries, int hops,
                        double baseConfidence, double finalConfidence,
                        boolean sufficient, double threshold) {
        StringBuilder sb = new StringBuilder("首跳置信度 ")
                .append(String.format("%.2f", baseConfidence));
        if (subQueries > 0) {
            sb.append("，改写 ").append(subQueries).append(" 条子查询");
        }
        sb.append("，共 ").append(hops).append(" 跳，最终置信度 ")
                .append(String.format("%.2f", finalConfidence));
        sb.append(sufficient ? "，证据充足" : "，低于阈值 " + String.format("%.2f", threshold)
                + "，答复需注明不确定");
        if (hops > 1) {
            sb.append("；实际查询：").append(String.join(" / ", executed));
        }
        return StrUtil.maxLength(sb.toString(), MAX_NOTE_CHARS);
    }
}
