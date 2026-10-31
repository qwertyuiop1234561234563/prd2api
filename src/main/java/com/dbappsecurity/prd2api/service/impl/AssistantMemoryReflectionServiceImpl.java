package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.entity.AssistantUserMemory;
import com.dbappsecurity.prd2api.service.AssistantMemoryReflectionService;
import com.dbappsecurity.prd2api.service.AssistantMemoryService;
import com.dbappsecurity.prd2api.service.AssistantMemoryService.MemoryItem;
import com.dbappsecurity.prd2api.service.LlmInvokeService;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 记忆反思实现：命中偏好表达时用一次模型调用提炼稳定偏好
 * <p>
 * 只有命中触发词才反思，避免每轮都多花一次模型调用；写入前做同值去重，
 * 同 key 不同值由记忆服务按“新值覆盖旧值”处理，保证用户偏好随对话演进。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantMemoryReflectionServiceImpl implements AssistantMemoryReflectionService {

    private static final int MAX_INPUT_CHARS = 300;
    private static final int MAX_KEY_CHARS = 16;
    private static final int MAX_VALUE_CHARS = 80;
    private static final int EXISTING_SCAN = 100;

    /** 潜在稳定偏好表达：时效词 + 约束词 */
    private static final Pattern TRIGGER = Pattern.compile(
            "(以后|今后|下次|每次|默认|习惯|偏好|不要|别再|尽量|一律|统一|以后都|先给|再给)");

    private static final String REFLECT_SYSTEM_TEMPLATE = """
            你是用户偏好提炼器。只从「用户本轮发言」提取能长期复用的稳定偏好、约束或工作习惯。
            不要提取：一次性请求（这次/这个任务）、临时参数、寒暄、与平台无关的闲聊、用户提问本身。
            最多 %d 条；没有可沉淀的偏好时输出 {"facts":[]}。
            key 用 2~6 字名词（如 输出格式/结论顺序/语气）；value 用不超过 40 字的一句话。
            只输出 JSON，禁止解释或 Markdown 代码块：{"facts":[{"key":"输出格式","value":"优先使用表格"}]}
            """;

    private final AssistantMemoryService memoryService;
    private final LlmInvokeService llmInvokeService;
    private final Prd2ApiProperties properties;
    private final ObjectMapper objectMapper;
    private final MetricUtil metricUtil;

    @Override
    public boolean triggered(String userMessage) {
        return StrUtil.isNotBlank(userMessage) && TRIGGER.matcher(userMessage).find();
    }

    @Override
    public List<MemoryItem> reflect(String userMessage) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isMemoryReflection() || !llmInvokeService.available()
                || !triggered(userMessage)) {
            return List.of();
        }
        int maxFacts = Math.max(1, config.getMemoryReflectionMaxFacts());
        List<MemoryItem> saved = new ArrayList<>();
        try {
            String raw = llmInvokeService.chat(String.format(REFLECT_SYSTEM_TEMPLATE, maxFacts),
                    "【用户本轮发言】" + StrUtil.maxLength(userMessage, MAX_INPUT_CHARS), "default");
            Map<String, String> facts = parse(raw, maxFacts);
            if (facts.isEmpty()) {
                metricUtil.agentMemoryReflection(0);
                return List.of();
            }
            Map<String, String> existing = existingMemory();
            for (Map.Entry<String, String> fact : facts.entrySet()) {
                String key = StrUtil.maxLength(fact.getKey(), MAX_KEY_CHARS);
                String value = StrUtil.maxLength(fact.getValue(), MAX_VALUE_CHARS);
                if (StrUtil.isBlank(key) || StrUtil.isBlank(value)) {
                    continue;
                }
                if (value.equals(existing.get(key))) {
                    // 同 key 同值说明偏好没变，不重复写入
                    continue;
                }
                MemoryItem item = memoryService.save(key, value, "REFLECTION");
                if (item != null) {
                    saved.add(item);
                }
            }
        } catch (Exception e) {
            log.warn("记忆反思失败，跳过本轮 msg={}", e.getMessage());
        }
        metricUtil.agentMemoryReflection(saved.size());
        if (!saved.isEmpty()) {
            log.info("记忆反思写入 count={} keys={}", saved.size(),
                    saved.stream().map(MemoryItem::key).toList());
        }
        return saved;
    }

    /**
     * 宽容解析：允许对象包裹、数组直出与前后夹带文字
     */
    private Map<String, String> parse(String raw, int maxFacts) {
        Map<String, String> facts = new LinkedHashMap<>();
        if (StrUtil.isBlank(raw)) {
            return facts;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        try {
            JsonNode root;
            if (start >= 0 && end > start) {
                root = objectMapper.readTree(raw.substring(start, end + 1));
            } else {
                int arrayStart = raw.indexOf('[');
                int arrayEnd = raw.lastIndexOf(']');
                if (arrayStart < 0 || arrayEnd <= arrayStart) {
                    return facts;
                }
                root = objectMapper.readTree(raw.substring(arrayStart, arrayEnd + 1));
            }
            JsonNode items = root.isArray() ? root : root.path("facts");
            if (!items.isArray()) {
                return facts;
            }
            for (JsonNode node : items) {
                if (facts.size() >= maxFacts) {
                    break;
                }
                String key = node.path("key").asText("").trim();
                String value = node.path("value").asText("").trim();
                if (StrUtil.isNotBlank(key) && StrUtil.isNotBlank(value) && value.length() >= 3) {
                    facts.put(key, value);
                }
            }
            return facts;
        } catch (Exception e) {
            return facts;
        }
    }

    /**
     * 现有记忆快照，用于同值去重
     */
    private Map<String, String> existingMemory() {
        Map<String, String> existing = new LinkedHashMap<>();
        List<AssistantUserMemory> memories;
        try {
            memories = memoryService.list(EXISTING_SCAN);
        } catch (Exception e) {
            return existing;
        }
        for (AssistantUserMemory memory : memories) {
            existing.put(memory.getMemoryKey(), memory.getMemoryValue());
        }
        return existing;
    }
}
