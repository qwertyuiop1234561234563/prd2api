package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.agent.tool.AssistantToolRegistry.ToolResult;
import com.dbappsecurity.prd2api.constant.AssistantActionType;
import com.dbappsecurity.prd2api.service.AssistantToolContractService;
import com.dbappsecurity.prd2api.service.LlmInvokeService.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 工具输出契约实现
 * <p>
 * 契约只覆盖本平台工具可控的字段与语义，规则全部确定性，不调用大模型：
 * 1) 成功结果不能是空摘要 / 原始 JSON；
 * 2) 结构化工具必须带 data，且关键字段类型正确；
 * 3) 检索工具“召回为空或证据不足却标记成功”视为伪成功，先改写检索词重检一次，仍不满足则降级为如实失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantToolContractServiceImpl implements AssistantToolContractService {

    /** 成功时必须带结构化数据、且字段结构固定的工具 */
    private static final Set<String> DATA_SCHEMA_TOOLS = Set.of(
            "search_knowledge", "recall_memory", "save_memory");

    /** 动作类工具：只有返回卡片动作时才要求 data，跳转/纯文本返回允许 data 为空 */
    private static final Set<String> DATA_ACTION_TYPES = Set.of(
            AssistantActionType.TASK_LIST, AssistantActionType.TASK_DETAIL, AssistantActionType.QUOTA,
            AssistantActionType.VIP, AssistantActionType.FILE_LIST, AssistantActionType.ADMIN_STATS);

    /** 检索词改写时剔除的口语噪声 */
    private static final String[] QUERY_NOISE = {
            "请问", "帮我", "麻烦", "一下", "告诉我", "我想知道", "我想", "想要", "怎么样", "怎么办",
            "是什么", "有哪些", "为什么", "如何", "怎么", "是否", "能不能", "可以吗", "吗", "呢",
            "？", "?", "，", ",", "。", "：", ":", "、", "的"};

    private static final int MAX_REPAIR_QUERY = 20;

    private final ObjectMapper objectMapper;

    @Override
    public Check check(String toolName, ToolResult result, String arguments) {
        String name = StrUtil.blankToDefault(toolName, "");
        if (result == null) {
            return new Check(false, List.of(new Violation("NULL_RESULT", "工具未返回结果")),
                    null, true, "工具未返回结果，按失败处理");
        }
        // 失败结果不校验：失败本身就是要回灌给模型的信号
        if (!result.success()) {
            return new Check(true, List.of(), null, false, null);
        }
        List<Violation> violations = new ArrayList<>();
        String summary = StrUtil.blankToDefault(result.summary(), "");
        if (StrUtil.isBlank(summary)) {
            violations.add(new Violation("EMPTY_SUMMARY", "成功但无摘要文本"));
        }
        if (looksLikeRawJson(summary)) {
            violations.add(new Violation("RAW_JSON", "摘要疑似原始 JSON，直接回灌会污染上下文"));
        }
        if (result.data() == null
                && (DATA_SCHEMA_TOOLS.contains(name) || DATA_ACTION_TYPES.contains(result.actionType()))) {
            violations.add(new Violation("MISSING_DATA", "成功但结构化 data 为空"));
        }
        JsonNode data = toJson(result.data());
        if ("search_knowledge".equals(name) && data != null) {
            JsonNode hits = data.path("hits");
            if (!hits.isArray()) {
                violations.add(new Violation("SCHEMA_HITS", "hits 字段缺失或不是数组"));
            } else if (hits.isEmpty()) {
                violations.add(new Violation("EMPTY_HITS", "检索结果为空却标记成功"));
            } else if (!data.path("sufficient").asBoolean(true)) {
                violations.add(new Violation("INSUFFICIENT_EVIDENCE", "召回证据不足却标记成功"));
            }
        }
        if ("recall_memory".equals(name) && data != null && !data.path("items").isArray()) {
            violations.add(new Violation("SCHEMA_ITEMS", "items 字段缺失或不是数组"));
        }
        if ("save_memory".equals(name) && data != null
                && (data.path("key").asText("").isBlank() || data.path("value").asText("").isBlank())) {
            violations.add(new Violation("SCHEMA_MEMORY", "key/value 字段缺失"));
        }
        if (violations.isEmpty()) {
            return new Check(true, List.of(), null, false, null);
        }

        // 结构化修复：证据不足时先改写检索词；检索词已经干净则扩大召回 topK 重检一次
        ToolCall repair = null;
        boolean evidenceIssue = violations.stream().anyMatch(violation ->
                "EMPTY_HITS".equals(violation.code()) || "INSUFFICIENT_EVIDENCE".equals(violation.code()));
        if ("search_knowledge".equals(name) && evidenceIssue) {
            String query = arg(arguments, "query");
            String rewritten = rewriteQuery(query);
            if (StrUtil.isBlank(query)) {
                repair = null;
            } else if (!rewritten.isBlank() && !rewritten.equals(query)) {
                repair = repairCall(rewritten, null);
            } else {
                repair = repairCall(query, 5);
            }
        }
        StringBuilder note = new StringBuilder("契约校验未通过：");
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) {
                note.append('、');
            }
            note.append(violations.get(i).detail());
        }
        note.append(repair == null ? "；结果不可直接用于作答" : "；已改写检索词自动重检一次");
        return new Check(false, violations, repair, true, note.toString());
    }

    @Override
    public ToolResult degrade(String toolName, ToolResult original, List<Violation> violations) {
        StringBuilder detail = new StringBuilder();
        for (Violation violation : violations) {
            if (!detail.isEmpty()) {
                detail.append('、');
            }
            detail.append(violation.detail());
        }
        String summary = "search_knowledge".equals(toolName)
                ? "知识库证据不足或检索结果异常（" + detail + "），请如实告知用户当前知识库未覆盖，不要编造。"
                : "工具结果不满足输出契约（" + detail + "），该结果不可用于作答；请改用其它工具，或如实告知无法确认。";
        return new ToolResult(false, summary, null, AssistantActionType.NONE, null, null);
    }

    private boolean looksLikeRawJson(String summary) {
        String text = StrUtil.blankToDefault(summary, "").trim();
        if (text.isEmpty()) {
            return false;
        }
        int occurrences = 0;
        int index = text.indexOf("{\"");
        while (index >= 0) {
            occurrences++;
            index = text.indexOf("{\"", index + 2);
        }
        return occurrences >= 2 || (text.startsWith("{") && text.endsWith("}") && text.contains(":\""));
    }

    private JsonNode toJson(Object data) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.valueToTree(data);
        } catch (Exception e) {
            return null;
        }
    }

    private String arg(String arguments, String field) {
        try {
            JsonNode node = objectMapper.readTree(StrUtil.blankToDefault(arguments, "{}"));
            return node.path(field).asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 检索词改写：去掉口语与标点，截断到核心短语，用于证据不足时的一次重检
     */
    private String rewriteQuery(String query) {
        String text = StrUtil.blankToDefault(query, "");
        for (String noise : QUERY_NOISE) {
            text = text.replace(noise, "");
        }
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= MAX_REPAIR_QUERY ? text : text.substring(0, MAX_REPAIR_QUERY).trim();
    }

    private ToolCall repairCall(String query, Integer topK) {
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("query", query);
            if (topK != null) {
                args.put("topK", topK);
            }
            return new ToolCall("contract_repair", "search_knowledge", objectMapper.writeValueAsString(args));
        } catch (Exception e) {
            return null;
        }
    }
}
