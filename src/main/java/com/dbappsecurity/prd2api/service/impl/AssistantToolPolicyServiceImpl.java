package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.service.AssistantToolPolicyService;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.SimilarityUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具选择经验策略实现
 * <p>
 * 存储按租户 + 用户隔离，条目上限与匹配阈值都可配置；任何异常都返回空推荐，
 * 经验层只做“加分项”，不影响原有语义检索的主路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantToolPolicyServiceImpl implements AssistantToolPolicyService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Prd2ApiProperties properties;
    private final MetricUtil metricUtil;

    @Override
    public void learn(String question, List<String> tools) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isToolPolicyEnabled()) {
            return;
        }
        String normalized = normalize(question);
        if (StrUtil.isBlank(normalized) || tools == null || tools.isEmpty()) {
            return;
        }
        try {
            String key = key();
            int max = Math.max(10, config.getToolPolicyMaxEntries());
            List<String> entries = stringRedisTemplate.opsForList().range(key, 0, max - 1);
            if (entries != null) {
                for (int i = 0; i < entries.size(); i++) {
                    PolicyEntry item = parse(entries.get(i));
                    if (item == null || !normalized.equals(item.question())) {
                        continue;
                    }
                    // 同一问法重复成功：工具集取并集并累加次数，避免经验被单次偶发覆盖
                    List<String> merged = new ArrayList<>(item.tools());
                    for (String tool : tools) {
                        if (!merged.contains(tool)) {
                            merged.add(tool);
                        }
                    }
                    String value = objectMapper.writeValueAsString(
                            new PolicyEntry(item.question(), merged, item.count() + 1));
                    stringRedisTemplate.opsForList().set(key, i, value);
                    return;
                }
            }
            stringRedisTemplate.opsForList().leftPush(key,
                    objectMapper.writeValueAsString(new PolicyEntry(normalized, new ArrayList<>(tools), 1)));
            stringRedisTemplate.opsForList().trim(key, 0, max - 1);
        } catch (Exception e) {
            log.warn("工具经验策略写入失败 msg={}", e.getMessage());
        }
    }

    @Override
    public List<String> preferred(String question) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isToolPolicyEnabled()) {
            return List.of();
        }
        String compact = normalize(question);
        if (StrUtil.isBlank(compact)) {
            return List.of();
        }
        try {
            String key = key();
            int max = Math.max(10, config.getToolPolicyMaxEntries());
            List<String> entries = stringRedisTemplate.opsForList().range(key, 0, max - 1);
            if (entries == null || entries.isEmpty()) {
                metricUtil.agentPolicy(false, 0);
                return List.of();
            }
            PolicyEntry best = null;
            double bestScore = 0.0D;
            for (String raw : entries) {
                PolicyEntry item = parse(raw);
                if (item == null) {
                    continue;
                }
                // 双向覆盖率取较大值：既能命中“问法被扩展”，也能命中“问法比历史更短”
                double score = Math.max(SimilarityUtil.coverage(compact, item.question()),
                        SimilarityUtil.coverage(item.question(), compact));
                if (score > bestScore) {
                    bestScore = score;
                    best = item;
                }
            }
            if (best == null || bestScore < config.getToolPolicyMinScore()) {
                metricUtil.agentPolicy(false, 0);
                return List.of();
            }
            log.info("工具经验策略命中 score={} tools={}", String.format("%.3f", bestScore), best.tools());
            metricUtil.agentPolicy(true, best.tools().size());
            return best.tools();
        } catch (Exception e) {
            log.warn("工具经验策略读取失败 msg={}", e.getMessage());
            return List.of();
        }
    }

    private PolicyEntry parse(String raw) {
        try {
            return StrUtil.isBlank(raw) ? null : objectMapper.readValue(raw, PolicyEntry.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 归一化问法：去空白与标点，限制长度，保证相似问法能落到同一条经验上
     */
    private String normalize(String question) {
        if (StrUtil.isBlank(question)) {
            return "";
        }
        return StrUtil.maxLength(question.replaceAll("[\\s，。？！,?!.、；;：:（）()]", ""), 40);
    }

    private String key() {
        String tenantId = StrUtil.blankToDefault(TenantContextUtil.getTenantId(), "default");
        String userKey = StrUtil.blankToDefault(TenantContextUtil.getUserKey(),
                StrUtil.blankToDefault(TenantContextUtil.getUserId(), "anon"));
        return RedisKeyConstant.ASSISTANT_TOOL_POLICY_PREFIX + tenantId + ":" + userKey;
    }

    /**
     * 经验条目
     */
    public record PolicyEntry(String question, List<String> tools, int count) {
    }
}
