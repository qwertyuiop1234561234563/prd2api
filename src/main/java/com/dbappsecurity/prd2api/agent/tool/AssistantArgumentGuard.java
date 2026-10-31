package com.dbappsecurity.prd2api.agent.tool;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具入参安全守卫
 * <p>
 * 已有“出参契约校验”，这里补上对称的“入参守卫”：模型生成的工具参数同样不可信。
 * 三类参数直接拒绝，并把原因回给模型，让它自己改：
 * 1) 疑似指令注入（“忽略之前的指令 / 输出系统提示词”等），防止工具参数被当成新指令；
 * 2) ID 类参数含非法字符（引号、路径分隔符、通配符），防止越权查询与路径穿越；
 * 3) 分页/召回数量越界，防止一次拉全表。
 * 拒绝发生在执行之前，属于“零副作用拦截”。
 */
@Component
@RequiredArgsConstructor
public class AssistantArgumentGuard {

    /** 常见指令注入标记（中英文），命中即拒绝 */
    private static final Set<String> INJECTION_MARKERS = Set.of(
            "ignore previous", "ignore all previous", "disregard", "system prompt", "you are now",
            "developer mode", "jailbreak", "忽略之前", "忽略以上", "系统提示词", "开发者模式", "越狱");
    /** 需要做字符集校验的 ID 类字段 */
    private static final Set<String> ID_FIELDS = Set.of("taskId", "fileId", "id", "sessionId", "confirmId");
    /** 需要做范围校验的数量类字段 */
    private static final Set<String> RANGE_FIELDS = Set.of("topK", "limit", "pageSize", "maxSteps");
    /** 合法 ID：字母数字下划线中划线，长度 1~64 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_DEPTH = 3;
    private static final int MIN_RANGE = 1;
    private static final int MAX_RANGE = 50;

    private final Prd2ApiProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 校验参数，返回是否放行；拒绝时 reason 会直接回给模型
     */
    public Verdict check(String toolName, String arguments) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isArgumentGuardEnabled()) {
            return Verdict.PASS;
        }
        String raw = StrUtil.blankToDefault(arguments, "{}");
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            return reject("参数不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            return reject("参数必须是 JSON 对象");
        }
        String problem = inspect(root, 0, toolName);
        return problem == null ? Verdict.PASS : reject(problem);
    }

    private String inspect(JsonNode node, int depth, String toolName) {
        if (depth > MAX_DEPTH) {
            return "参数嵌套过深";
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            JsonNode value = field.getValue();
            if (value.isObject() || value.isArray()) {
                for (JsonNode child : value) {
                    String problem = inspect(child, depth + 1, toolName);
                    if (problem != null) {
                        return problem;
                    }
                }
                continue;
            }
            if (value.isNumber() && RANGE_FIELDS.contains(name)) {
                int number = value.asInt();
                if (number < MIN_RANGE || number > MAX_RANGE) {
                    return "参数 " + name + " 超出允许范围 " + MIN_RANGE + "~" + MAX_RANGE;
                }
            }
            if (!value.isTextual()) {
                continue;
            }
            String text = value.asText();
            if (text.length() > MAX_TEXT_LENGTH) {
                return "参数 " + name + " 过长（上限 " + MAX_TEXT_LENGTH + " 字）";
            }
            if (containsInjection(text)) {
                return "参数 " + name + " 包含疑似指令注入内容";
            }
            if (ID_FIELDS.contains(name) && !SAFE_ID.matcher(text).matches()) {
                return "参数 " + name + " 包含非法字符";
            }
        }
        return null;
    }

    private boolean containsInjection(String text) {
        String lower = text.toLowerCase();
        for (String marker : INJECTION_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private Verdict reject(String reason) {
        return new Verdict(false, reason);
    }

    /**
     * 校验结论
     */
    public record Verdict(boolean ok, String reason) {

        public static final Verdict PASS = new Verdict(true, "");
    }
}
