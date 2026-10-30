package com.dbappsecurity.prd2api.agent.tool;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.constant.AssistantActionType;
import com.dbappsecurity.prd2api.constant.AssistantIntentEnum;
import com.dbappsecurity.prd2api.rag.KnowledgeBaseService;
import com.dbappsecurity.prd2api.service.AssistantActionService;
import com.dbappsecurity.prd2api.service.AssistantMemoryService;
import com.dbappsecurity.prd2api.util.AssistantIntentResolver;
import com.dbappsecurity.prd2api.vo.RagChunkVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具注册表
 * <p>
 * 只暴露白名单工具，模型无法调用任意方法；系统查询类工具复用既有动作服务，
 * 保证租户隔离、权限校验与前端动作卡片和规则通道完全一致。
 * 工具返回值包含一段可供模型阅读的 summary 以及结构化 data。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantToolRegistry {

    private static final Map<String, String> TOOL_TITLES = Map.ofEntries(
            Map.entry("query_my_tasks", "查询我的任务"),
            Map.entry("get_task_status", "查询任务进度"),
            Map.entry("cancel_task", "取消任务"),
            Map.entry("retry_task", "重试任务"),
            Map.entry("query_my_quota", "查询我的额度"),
            Map.entry("query_my_vip", "查询会员信息"),
            Map.entry("list_my_files", "查看我的文件"),
            Map.entry("query_admin_stats", "查询运营数据"),
            Map.entry("search_knowledge", "检索平台知识库"),
            Map.entry("save_memory", "记住用户信息"),
            Map.entry("recall_memory", "召回用户记忆"),
            Map.entry("navigate_page", "打开平台页面"));

    /** 工具风险等级：只读 / 写 / 危险（危险操作必须人工确认后执行） */
    public enum Risk {
        READ, WRITE, DANGER
    }

    private static final Map<String, Risk> TOOL_RISK = Map.of(
            "cancel_task", Risk.DANGER,
            "retry_task", Risk.DANGER,
            "save_memory", Risk.WRITE);

    private final ObjectMapper objectMapper;
    private final AssistantActionService actionService;
    private final AssistantIntentResolver intentResolver;
    private final AssistantMemoryService memoryService;
    private final KnowledgeBaseService knowledgeBaseService;

    private String definitionsJson;

    @PostConstruct
    void initDefinitions() {
        ArrayNode tools = objectMapper.createArrayNode();
        tools.add(function("query_my_tasks", "查询当前用户最近的 PRD 解析任务、状态与进度。用户问“我的任务/解析到哪了”时优先调用。",
                Map.of(), List.of()));
        tools.add(function("get_task_status", "按任务 ID 查询单个任务的进度、失败原因或结果状态。",
                Map.of("taskId", "32 位十六进制任务 ID"), List.of("taskId")));
        tools.add(function("cancel_task", "取消一个正在排队或解析中的任务，执行前需确认任务 ID。",
                Map.of("taskId", "32 位十六进制任务 ID"), List.of("taskId")));
        tools.add(function("retry_task", "重试一个失败的任务，会重新创建解析流程。",
                Map.of("taskId", "32 位十六进制任务 ID"), List.of("taskId")));
        tools.add(function("query_my_quota", "查询当前用户今日上传次数、token 用量、并发与单文件大小额度。",
                Map.of(), List.of()));
        tools.add(function("query_my_vip", "查询当前用户的会员套餐、到期时间与权益。",
                Map.of(), List.of()));
        tools.add(function("list_my_files", "列出当前用户最近上传的文件及大小、时间。",
                Map.of(), List.of()));
        tools.add(function("query_admin_stats", "查询近 7 天全平台运营数据，仅管理员可用。",
                Map.of(), List.of()));
        tools.add(function("search_knowledge", "检索平台知识库，回答上传格式、四阶段解析、导出、VIP、安全、架构等平台问题，返回带来源的片段。",
                Map.of("query", "检索关键词或用户问题"), List.of("query")));
        tools.add(function("save_memory", "当用户要求记住偏好、称呼或稳定事实时写入长期记忆；key 用简短名词，value 用一句话。",
                Map.of("key", "记忆键，如 称呼/偏好/常用项目", "value", "记忆内容"), List.of("key", "value")));
        tools.add(function("recall_memory", "召回当前用户已保存的长期记忆，回答涉及用户偏好或称呼的问题前可调用。",
                Map.of("query", "召回关键词，可留空返回最近记忆"), List.of()));
        tools.add(function("navigate_page", "返回一个前端可点击的页面跳转动作。",
                Map.of("page", "upload / tasks / vip / report / admin"), List.of("page")));
        definitionsJson = tools.toString();
        log.info("Agent 工具注册完成 count={}", tools.size());
    }

    /**
     * 工具定义 JSON（OpenAI tools 数组）
     */
    public String definitionsJson() {
        return definitionsJson;
    }

    /**
     * 工具清单，供能力接口展示
     */
    public List<ToolInfo> listTools() {
        List<ToolInfo> tools = new ArrayList<>(TOOL_TITLES.size());
        TOOL_TITLES.forEach((name, title) -> tools.add(new ToolInfo(name, title, riskOf(name).name())));
        return tools;
    }

    public String title(String toolName) {
        return TOOL_TITLES.getOrDefault(toolName, StrUtil.blankToDefault(toolName, "工具调用"));
    }

    /**
     * 工具是否在白名单内（确认票据执行前必须校验）
     */
    public boolean supports(String toolName) {
        return TOOL_TITLES.containsKey(StrUtil.blankToDefault(toolName, ""));
    }

    /**
     * 工具风险等级，未登记的按只读处理
     */
    public Risk riskOf(String toolName) {
        return TOOL_RISK.getOrDefault(StrUtil.blankToDefault(toolName, ""), Risk.READ);
    }

    /**
     * 是否需要人工确认：危险操作，或提示词注入命中时把写操作一并升级为确认
     */
    public boolean requiresConfirm(String toolName, boolean forceConfirm) {
        Risk risk = riskOf(toolName);
        return risk == Risk.DANGER || (forceConfirm && risk == Risk.WRITE);
    }

    /**
     * 执行工具：所有异常收敛为可读结果，交给模型继续决策，避免整轮对话失败
     */
    public ToolResult execute(String name, String argumentsJson) {
        String toolName = StrUtil.blankToDefault(name, "");
        JsonNode args = parseArgs(argumentsJson);
        try {
            return switch (toolName) {
                case "query_my_tasks" -> fromAction(actionService.execute(AssistantIntentEnum.TASK_LIST, ""));
                case "get_task_status" -> fromAction(actionService.execute(
                        AssistantIntentEnum.TASK_STATUS, arg(args, "taskId")));
                case "cancel_task" -> requireTaskId(args, AssistantIntentEnum.CANCEL_TASK);
                case "retry_task" -> requireTaskId(args, AssistantIntentEnum.RETRY_TASK);
                case "query_my_quota" -> fromAction(actionService.execute(AssistantIntentEnum.QUOTA, ""));
                case "query_my_vip" -> fromAction(actionService.execute(AssistantIntentEnum.VIP, ""));
                case "list_my_files" -> fromAction(actionService.execute(AssistantIntentEnum.FILE_LIST, ""));
                case "query_admin_stats" -> fromAction(actionService.execute(AssistantIntentEnum.ADMIN_STATS, ""));
                case "search_knowledge" -> searchKnowledge(arg(args, "query"));
                case "save_memory" -> saveMemory(args);
                case "recall_memory" -> recallMemory(arg(args, "query"));
                case "navigate_page" -> navigate(arg(args, "page"));
                default -> new ToolResult(false, "未知工具：" + toolName, null,
                        AssistantActionType.NONE, null, null);
            };
        } catch (Exception e) {
            log.warn("Agent 工具执行失败 tool={} msg={}", toolName, e.getMessage());
            return new ToolResult(false, "工具执行失败：" + StrUtil.maxLength(e.getMessage(), 120), null,
                    AssistantActionType.NONE, null, null);
        }
    }

    private ToolResult requireTaskId(JsonNode args, AssistantIntentEnum intent) {
        String taskId = arg(args, "taskId");
        if (StrUtil.isBlank(taskId) || intentResolver.extractTaskId(taskId) == null) {
            return new ToolResult(false, "缺少有效的 32 位任务 ID，请先向用户确认任务 ID。", null,
                    AssistantActionType.NONE, null, null);
        }
        return fromAction(actionService.execute(intent, taskId));
    }

    private ToolResult fromAction(AssistantActionService.ActionResult result) {
        if (result == null) {
            return new ToolResult(false, "没有可执行的动作", null,
                    AssistantActionType.NONE, null, null);
        }
        return new ToolResult(true, result.reply(), result.data(),
                result.actionType(), result.actionLabel(), result.actionTarget());
    }

    private ToolResult searchKnowledge(String query) {
        if (StrUtil.isBlank(query)) {
            return new ToolResult(false, "缺少检索关键词", null,
                    AssistantActionType.NONE, null, null);
        }
        List<RagChunkVO> hits = knowledgeBaseService.search(query, 3);
        if (hits.isEmpty()) {
            return new ToolResult(false, "知识库没有检索到相关内容，请如实告诉用户不确定，并建议联系管理员。",
                    null, AssistantActionType.NONE, null, null);
        }
        StringBuilder summary = new StringBuilder("知识库检索结果：\n");
        for (RagChunkVO hit : hits) {
            summary.append("- 【").append(hit.getSource()).append("】")
                    .append(StrUtil.maxLength(hit.getContent(), 400))
                    .append('\n');
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hits", hits);
        return new ToolResult(true, summary.toString(), data, AssistantActionType.NONE, null, null);
    }

    private ToolResult saveMemory(JsonNode args) {
        String key = arg(args, "key");
        String value = arg(args, "value");
        AssistantMemoryService.MemoryItem item = memoryService.save(key, value, "AGENT");
        if (item == null) {
            return new ToolResult(false, "记忆键和内容不能为空", null,
                    AssistantActionType.NONE, null, null);
        }
        return new ToolResult(true, "已记住：" + item.key() + " = " + item.value(),
                Map.of("key", item.key(), "value", item.value()),
                AssistantActionType.NONE, null, null);
    }

    private ToolResult recallMemory(String query) {
        List<AssistantMemoryService.MemoryItem> items = memoryService.recall(query, 5);
        if (items.isEmpty()) {
            return new ToolResult(true, "当前用户还没有长期记忆。", Map.of("items", List.of()),
                    AssistantActionType.NONE, null, null);
        }
        StringBuilder summary = new StringBuilder("用户长期记忆：\n");
        for (AssistantMemoryService.MemoryItem item : items) {
            summary.append("- ").append(item.key()).append("：").append(item.value()).append('\n');
        }
        List<Map<String, String>> data = items.stream()
                .map(item -> Map.of("key", item.key(), "value", item.value()))
                .toList();
        return new ToolResult(true, summary.toString(), Map.of("items", data),
                AssistantActionType.NONE, null, null);
    }

    private ToolResult navigate(String page) {
        String normalized = StrUtil.blankToDefault(page, "").toLowerCase();
        String target;
        String label;
        switch (normalized) {
            case "upload" -> {
                target = "/upload";
                label = "去上传";
            }
            case "tasks" -> {
                target = "/tasks";
                label = "去任务列表";
            }
            case "vip" -> {
                target = "/vip";
                label = "去会员中心";
            }
            case "report" -> {
                target = "/tasks";
                label = "查看报告";
            }
            case "admin" -> {
                target = "/admin";
                label = "打开管理后台";
            }
            default -> {
                return new ToolResult(false, "不支持的页面：" + page, null,
                        AssistantActionType.NONE, null, null);
            }
        }
        return new ToolResult(true, "已准备好跳转动作：" + label,
                Map.of("page", normalized), AssistantActionType.NAVIGATE, label, target);
    }

    private ObjectNode function(String name, String description,
                                Map<String, String> properties, List<String> required) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", name);
        function.put("description", description);
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode props = parameters.putObject("properties");
        properties.forEach((key, value) -> {
            ObjectNode property = props.putObject(key);
            property.put("type", "string");
            property.put("description", value);
        });
        if (!required.isEmpty()) {
            ArrayNode requiredArray = parameters.putArray("required");
            required.forEach(requiredArray::add);
        }
        return tool;
    }

    private JsonNode parseArgs(String argumentsJson) {
        if (StrUtil.isBlank(argumentsJson)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            return node == null || !node.isObject() ? objectMapper.createObjectNode() : node;
        } catch (Exception e) {
            log.warn("工具入参解析失败，按空参处理 args={}", StrUtil.maxLength(argumentsJson, 120));
            return objectMapper.createObjectNode();
        }
    }

    private String arg(JsonNode args, String field) {
        return StrUtil.blankToDefault(args.path(field).asText(""), "").trim();
    }

    /**
     * 工具执行结果
     */
    public record ToolResult(boolean success, String summary, Object data,
                             String actionType, String actionLabel, String actionTarget) {
    }

    /**
     * 工具简介
     */
    public record ToolInfo(String name, String title, String risk) {
    }
}
