package com.dbappsecurity.prd2api.agent.tool;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.constant.AssistantActionType;
import com.dbappsecurity.prd2api.constant.AssistantIntentEnum;
import com.dbappsecurity.prd2api.service.AssistantActionService;
import com.dbappsecurity.prd2api.service.AssistantMemoryService;
import com.dbappsecurity.prd2api.service.AssistantRetrievalService;
import com.dbappsecurity.prd2api.util.AssistantIntentResolver;
import com.dbappsecurity.prd2api.util.PiiMaskUtil;
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
import java.util.Set;

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
            Map.entry("search_knowledge", "检索知识库（内置 + 本租户上传）"),
            Map.entry("save_memory", "记住用户信息"),
            Map.entry("recall_memory", "召回用户记忆"),
            Map.entry("navigate_page", "打开平台页面"),
            Map.entry("delegate_task", "委派专家子任务"));

    /** 工具风险等级：只读 / 写 / 危险（危险操作必须人工确认后执行） */
    public enum Risk {
        READ, WRITE, DANGER
    }

    private static final Map<String, Risk> TOOL_RISK = Map.of(
            "cancel_task", Risk.DANGER,
            "retry_task", Risk.DANGER,
            "save_memory", Risk.WRITE);

    /** 工具必填参数：模型漏参时不再盲目执行，而是向用户追问 */
    private static final Map<String, List<String>> TOOL_REQUIRED = Map.of(
            "get_task_status", List.of("taskId"),
            "cancel_task", List.of("taskId"),
            "retry_task", List.of("taskId"),
            "search_knowledge", List.of("query"),
            "save_memory", List.of("key", "value"),
            "navigate_page", List.of("page"),
            "delegate_task", List.of("expert", "goal"));

    /** 子 Agent 能力边界：每个专家只拿到自己领域的只读工具，避免越权与副作用 */
    public static final Map<String, List<String>> EXPERT_TOOLS = Map.of(
            "data_analyst", List.of("query_my_tasks", "get_task_status", "query_my_quota",
                    "query_my_vip", "list_my_files"),
            "doc_writer", List.of("search_knowledge", "recall_memory"));

    private static final Map<String, String> EXPERT_PROMPTS = Map.of(
            "data_analyst", """
                    你是「数据分析师」子 Agent。只负责调用只读工具查询任务、额度、文件、会员等实时数据，
                    汇总成结构化事实交给上层 Agent。不要写操作、不要编造数字；查不到就说查不到。
                    """,
            "doc_writer", """
                    你是「文档撰写」子 Agent。只负责检索平台知识库、结合用户长期记忆撰写周报/总结/说明类文本。
                    必须标注知识来源（《文档名》），不得编造平台事实与数字。
                    """);

    /** 工具名 → 定义节点，供动态检索按名字裁剪工具集 */
    private final Map<String, ObjectNode> definitionNodes = new LinkedHashMap<>();
    /** 工具名 → 描述，供 embedding 选工具 */
    private final Map<String, String> toolDescriptions = new LinkedHashMap<>();

    private final ObjectMapper objectMapper;
    private final AssistantActionService actionService;
    private final AssistantIntentResolver intentResolver;
    private final AssistantMemoryService memoryService;
    private final AssistantRetrievalService retrievalService;

    private String definitionsJson;

    @PostConstruct
    void initDefinitions() {
        ArrayNode tools = objectMapper.createArrayNode();
        add(tools, "query_my_tasks", "查询当前用户最近的 PRD 解析任务、状态与进度。用户问“我的任务/解析到哪了”时优先调用。",
                Map.of(), List.of());
        add(tools, "get_task_status", "按任务 ID 查询单个任务的进度、失败原因或结果状态。",
                Map.of("taskId", "32 位十六进制任务 ID"), List.of("taskId"));
        add(tools, "cancel_task", "取消一个正在排队或解析中的任务，执行前需确认任务 ID。",
                Map.of("taskId", "32 位十六进制任务 ID"), List.of("taskId"));
        add(tools, "retry_task", "重试一个失败的任务，会重新创建解析流程。",
                Map.of("taskId", "32 位十六进制任务 ID"), List.of("taskId"));
        add(tools, "query_my_quota", "查询当前用户今日上传次数、token 用量、并发与单文件大小额度。",
                Map.of(), List.of());
        add(tools, "query_my_vip", "查询当前用户的会员套餐、到期时间与权益。",
                Map.of(), List.of());
        add(tools, "list_my_files", "列出当前用户最近上传的文件及大小、时间。",
                Map.of(), List.of());
        add(tools, "query_admin_stats", "查询近 7 天全平台运营数据，仅管理员可用。",
                Map.of(), List.of());
        add(tools, "search_knowledge", "检索知识库：平台内置语料 + 本租户上传的离线文档（md/txt/docx/pdf），"
                + "回答平台功能、上传格式、四阶段解析、导出、VIP、安全、架构以及上传文档中的业务约定，返回带来源的片段。",
                Map.of("query", "检索关键词或用户问题"), List.of("query"));
        add(tools, "save_memory", "当用户要求记住偏好、称呼或稳定事实时写入长期记忆；key 用简短名词，value 用一句话。",
                Map.of("key", "记忆键，如 称呼/偏好/常用项目", "value", "记忆内容"), List.of("key", "value"));
        add(tools, "recall_memory", "召回当前用户已保存的长期记忆，回答涉及用户偏好或称呼的问题前可调用。",
                Map.of("query", "召回关键词，可留空返回最近记忆"), List.of());
        add(tools, "navigate_page", "返回一个前端可点击的页面跳转动作。",
                Map.of("page", "upload / tasks / vip / report / admin"), List.of("page"));
        add(tools, "delegate_task", "把子目标委派给领域专家子 Agent 执行。data_analyst 负责多步查询任务、额度、文件、会员数据并汇总事实；"
                        + "doc_writer 负责检索知识库并结合用户记忆撰写周报/总结。仅当任务需要“先查多项数据再成文”或多领域组合时使用。"
                        + "同一轮可以多次调用本工具并行委派多个专家，系统会并发执行后再统一汇总。",
                Map.of("expert", "data_analyst 或 doc_writer", "goal", "子目标，包含必要参数，用一句完整中文描述"),
                List.of("expert", "goal"));
        definitionsJson = tools.toString();
        log.info("Agent 工具注册完成 count={}", tools.size());
    }

    private void add(ArrayNode tools, String name, String description,
                     Map<String, String> properties, List<String> required) {
        ObjectNode node = function(name, description, properties, required);
        tools.add(node);
        definitionNodes.put(name, node);
        toolDescriptions.put(name, description);
    }

    /**
     * 工具定义 JSON（OpenAI tools 数组），全量
     */
    public String definitionsJson() {
        return definitionsJson;
    }

    /**
     * 只输出指定工具的定义 JSON，供工具动态检索裁剪 prompt
     */
    public String definitionsJson(List<String> names) {
        if (names == null || names.isEmpty() || names.size() >= definitionNodes.size()) {
            return definitionsJson;
        }
        ArrayNode tools = objectMapper.createArrayNode();
        for (String name : names) {
            ObjectNode node = definitionNodes.get(name);
            if (node != null) {
                tools.add(node);
            }
        }
        return tools.toString();
    }

    public List<String> names() {
        return new ArrayList<>(definitionNodes.keySet());
    }

    public String descriptionOf(String toolName) {
        return toolDescriptions.getOrDefault(StrUtil.blankToDefault(toolName, ""), "");
    }

    /**
     * 模型给出但缺失的必填参数
     */
    public List<String> missingRequired(String toolName, String argumentsJson) {
        List<String> required = TOOL_REQUIRED.getOrDefault(StrUtil.blankToDefault(toolName, ""), List.of());
        if (required.isEmpty()) {
            return List.of();
        }
        JsonNode args = parseArgs(argumentsJson);
        List<String> missing = new ArrayList<>();
        for (String field : required) {
            String value = arg(args, field);
            // 任务 ID 还要求格式合法：无效 ID 不发起工具调用，直接转追问
            boolean invalidTaskId = "taskId".equals(field) && StrUtil.isNotBlank(value)
                    && intentResolver.extractTaskId(value) == null;
            if (StrUtil.isBlank(value) || invalidTaskId) {
                missing.add(field);
            }
        }
        return missing;
    }

    public Set<String> experts() {
        return EXPERT_TOOLS.keySet();
    }

    public List<String> expertTools(String expert) {
        return EXPERT_TOOLS.getOrDefault(StrUtil.blankToDefault(expert, ""), List.of());
    }

    public String expertPrompt(String expert) {
        return EXPERT_PROMPTS.getOrDefault(StrUtil.blankToDefault(expert, ""), "");
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
                case "search_knowledge" -> searchKnowledge(arg(args, "query"), args.path("topK").asInt(3));
                case "save_memory" -> saveMemory(args);
                case "recall_memory" -> recallMemory(arg(args, "query"));
                case "navigate_page" -> navigate(arg(args, "page"));
                case "delegate_task" -> new ToolResult(false, "委派工具由 Agent 编排层执行，不能直接调用。", null,
                        AssistantActionType.NONE, null, null);
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

    private ToolResult searchKnowledge(String query, int topK) {
        if (StrUtil.isBlank(query)) {
            return new ToolResult(false, "缺少检索关键词", null,
                    AssistantActionType.NONE, null, null);
        }
        // 走修正检索：首跳置信度不足时自动改写查询补检，并回传证据是否充足
        // topK 是内部契约修复用的扩召回参数（默认 3，最大 5），不暴露给模型自由发挥
        AssistantRetrievalService.Retrieval retrieval = retrievalService.retrieve(query,
                Math.min(5, Math.max(1, topK)));
        List<RagChunkVO> hits = retrieval.chunks();
        if (hits.isEmpty()) {
            return new ToolResult(false, "知识库没有检索到相关内容，请如实告诉用户不确定，并建议联系管理员。"
                    + "（" + retrieval.note() + "）",
                    null, AssistantActionType.NONE, null, null);
        }
        StringBuilder summary = new StringBuilder("知识库检索结果（")
                .append(retrieval.note()).append("）：\n");
        for (RagChunkVO hit : hits) {
            summary.append("- 【").append(hit.getSource()).append("】")
                    .append(StrUtil.maxLength(hit.getContent(), 400))
                    .append('\n');
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hits", hits);
        data.put("retrieval", retrieval.note());
        data.put("sufficient", retrieval.sufficient());
        return new ToolResult(true, summary.toString(), data, AssistantActionType.NONE, null, null);
    }

    private ToolResult saveMemory(JsonNode args) {
        String key = arg(args, "key");
        // 长期记忆会长期留存，写入前先做 PII 脱敏，避免手机号/邮箱等敏感信息落库
        String value = PiiMaskUtil.mask(arg(args, "value"));
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
