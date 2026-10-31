package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolRegistry;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.rag.EmbeddingService;
import com.dbappsecurity.prd2api.service.AssistantToolSelector;
import com.dbappsecurity.prd2api.service.AssistantToolPolicyService;
import com.dbappsecurity.prd2api.util.MetricUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具动态检索实现
 * <p>
 * 工具描述向量只在首次使用时批量算一次并常驻内存（进程内缓存），不会为每次对话多付一次 embedding；
 * 任何异常都退化为全量注入，保证检索层永远不会阻断对话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantToolSelectorImpl implements AssistantToolSelector {

    /** 核心工具：始终注入，保证“查任务/查额度/查知识库”这类高频能力不因检索漏选而缺失 */
    private static final Set<String> CORE_TOOLS = Set.of(
            "query_my_tasks", "query_my_quota", "search_knowledge");

    private final AssistantToolRegistry toolRegistry;
    private final EmbeddingService embeddingService;
    private final Prd2ApiProperties properties;
    private final MetricUtil metricUtil;
    private final AssistantToolPolicyService toolPolicyService;

    /** 工具描述向量缓存，key = 工具名 */
    private final Map<String, float[]> vectorCache = new ConcurrentHashMap<>();

    @Override
    public Selection select(String question) {
        List<String> all = toolRegistry.names();
        // 经验策略：从历史成功轨迹里召回“相似问法用过的工具”，作为语义检索的补充
        List<String> policyTools = toolPolicyService.preferred(question).stream()
                .filter(all::contains).distinct().toList();
        boolean policyHit = !policyTools.isEmpty();
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isToolRouting() || all.size() <= config.getToolTopK()
                || StrUtil.isBlank(question)) {
            return new Selection(all, toolRegistry.definitionsJson(), false, all.size(),
                    policyHit, policyTools);
        }
        float[] questionVector = safeEmbed(question);
        if (questionVector.length == 0) {
            return new Selection(all, toolRegistry.definitionsJson(), false, all.size(),
                    policyHit, policyTools);
        }
        ensureToolVectors(all);

        List<Map.Entry<String, Double>> scored = new ArrayList<>(all.size());
        for (String name : all) {
            float[] vector = vectorCache.get(name);
            if (vector == null || vector.length == 0) {
                continue;
            }
            scored.add(new java.util.AbstractMap.SimpleEntry<>(name, cosine(questionVector, vector)));
        }
        scored.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed());

        Set<String> selected = new LinkedHashSet<>(CORE_TOOLS);
        // 经验命中优先占位，避免语义分略低被 top-k 截掉
        selected.addAll(policyTools);
        for (Map.Entry<String, Double> entry : scored) {
            if (selected.size() >= Math.max(1, config.getToolTopK()) + CORE_TOOLS.size()) {
                break;
            }
            if (entry.getValue() >= config.getToolMinScore() || CORE_TOOLS.contains(entry.getKey())) {
                selected.add(entry.getKey());
            }
        }
        if (selected.size() >= all.size()) {
            return new Selection(all, toolRegistry.definitionsJson(), false, all.size(),
                    policyHit, policyTools);
        }
        List<String> names = selected.stream().filter(all::contains).toList();
        metricUtil.agentToolRoute(true, all.size(), names.size());
        log.info("工具动态检索 candidate={} selected={} policyHit={} {}", all.size(), names.size(),
                policyHit, names);
        return new Selection(names, toolRegistry.definitionsJson(names), true, all.size(),
                policyHit, policyTools);
    }

    /**
     * 首次使用时批量算工具描述向量，失败则永不重试成功前保持退化全量
     */
    private void ensureToolVectors(List<String> all) {
        if (all.stream().allMatch(vectorCache::containsKey)) {
            return;
        }
        List<String> missing = all.stream().filter(name -> !vectorCache.containsKey(name)).toList();
        List<String> texts = missing.stream().map(toolRegistry::descriptionOf).toList();
        try {
            List<float[]> vectors = embeddingService.embedBatch(texts);
            for (int i = 0; i < missing.size() && i < vectors.size(); i++) {
                vectorCache.put(missing.get(i), vectors.get(i));
            }
        } catch (Exception e) {
            log.warn("工具描述向量计算失败，本轮退化为全量注入 msg={}", e.getMessage());
        }
    }

    private float[] safeEmbed(String text) {
        try {
            return embeddingService.embed(text);
        } catch (Exception e) {
            log.warn("问题向量计算失败，本轮退化为全量注入 msg={}", e.getMessage());
            return new float[0];
        }
    }

    private double cosine(float[] a, float[] b) {
        if (a.length == 0 || a.length != b.length) {
            return 0.0D;
        }
        double dot = 0.0D;
        double normA = 0.0D;
        double normB = 0.0D;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA <= 0 || normB <= 0) {
            return 0.0D;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
