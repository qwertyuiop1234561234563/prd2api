package com.dbappsecurity.prd2api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.config.AsyncThreadPoolConfig;
import com.dbappsecurity.prd2api.dto.AssistantChatDTO;
import com.dbappsecurity.prd2api.entity.AssistantUserMemory;
import com.dbappsecurity.prd2api.service.AssistantAgentService;
import com.dbappsecurity.prd2api.service.AssistantEvalService;
import com.dbappsecurity.prd2api.service.AssistantMemoryService;
import com.dbappsecurity.prd2api.service.AssistantService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.AgentStepVO;
import com.dbappsecurity.prd2api.vo.AssistantCapabilityVO;
import com.dbappsecurity.prd2api.vo.AssistantMemoryVO;
import com.dbappsecurity.prd2api.vo.AssistantMessageVO;
import com.dbappsecurity.prd2api.vo.AssistantReplyVO;
import com.dbappsecurity.prd2api.vo.AssistantSessionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 桌面助手接口：页面小人的对话框后端
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/assistant")
@Tag(name = "桌面助手", description = "页面小人问答：快捷查询、任务操作与平台答疑")
public class AssistantController {

    /** SSE 连接最长保持时间，避免死连接长期占用线程 */
    private static final long STREAM_TIMEOUT_MS = 90_000L;

    private final AssistantService assistantService;
    private final AssistantMemoryService memoryService;
    private final AssistantEvalService evalService;
    private final ThreadPoolTaskExecutor llmExecutor;

    public AssistantController(AssistantService assistantService,
                               AssistantMemoryService memoryService,
                               AssistantEvalService evalService,
                               @Qualifier(AsyncThreadPoolConfig.LLM_EXECUTOR) ThreadPoolTaskExecutor llmExecutor) {
        this.assistantService = assistantService;
        this.memoryService = memoryService;
        this.evalService = evalService;
        this.llmExecutor = llmExecutor;
    }

    @Operation(summary = "提问",
            description = "识别意图：任务/额度/文件/会员等操作类问题直接查询执行，其余走大模型，"
                    + "未配置模型时回落内置知识库；返回文本与前端动作（卡片或跳转）")
    @PostMapping("/chat")
    public RespModel<AssistantReplyVO> chat(@Valid @RequestBody AssistantChatDTO dto) {
        return RespModel.success(assistantService.chat(dto));
    }

    @Operation(summary = "流式提问（SSE）",
            description = "逐步推送 phase（模型思考中）与 step（工具执行完成/等待确认），最后推送 final 完整回复，"
                    + "前端可边执行边展示 Agent 过程")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AssistantChatDTO dto) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        // 请求线程的租户上下文不会自动带到线程池，先捕获再在新线程里恢复
        String userId = TenantContextUtil.getUserId();
        String tenantId = TenantContextUtil.getTenantId();
        String userKey = TenantContextUtil.getUserKey();
        String role = TenantContextUtil.getRole();
        llmExecutor.execute(() -> {
            TenantContextUtil.set(userId, tenantId, userKey, role);
            try {
                sendEvent(emitter, "phase", Map.of("phase", "START", "stepNo", 0));
                AssistantReplyVO vo = assistantService.chat(dto, new AssistantAgentService.AgentListener() {
                    @Override
                    public void onPhase(int stepNo, String phase) {
                        sendEvent(emitter, "phase", Map.of("phase", phase, "stepNo", stepNo));
                    }

                    @Override
                    public void onStep(AgentStepVO step) {
                        sendEvent(emitter, "step", step);
                    }
                });
                sendEvent(emitter, "final", vo);
                emitter.complete();
            } catch (Exception e) {
                log.warn("流式提问失败 msg={}", e.getMessage());
                sendEvent(emitter, "error", Map.of("message", StrUtil.maxLength(e.getMessage(), 200)));
                emitter.complete();
            } finally {
                TenantContextUtil.clear();
            }
        });
        return emitter;
    }

    @Operation(summary = "确认危险操作", description = "消费一次性确认票据后执行工具；票据过期或已被使用会直接拒绝")
    @PostMapping("/confirm/{confirmId}")
    public RespModel<AssistantReplyVO> confirm(
            @Parameter(description = "确认票据ID") @PathVariable String confirmId) {
        return RespModel.success(assistantService.confirm(confirmId));
    }

    @Operation(summary = "Agent 评测用例集", description = "返回黄金集，便于查看评测覆盖范围")
    @GetMapping("/eval/cases")
    public RespModel<List<com.dbappsecurity.prd2api.constant.AssistantEvalCaseConstant.EvalCase>> evalCases() {
        return RespModel.success(evalService.cases());
    }

    @Operation(summary = "运行 Agent 评测", description = "串行跑黄金集，输出通过率、工具选择准确率、token 与延迟")
    @PostMapping("/eval/run")
    public RespModel<AssistantEvalService.Report> runEval(
            @Parameter(description = "最多跑几条，默认全部")
            @RequestParam(defaultValue = "0") @Min(0) @Max(20) int limit) {
        return RespModel.success(evalService.run(limit));
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 推送失败 name={} msg={}", name, e.getMessage());
        }
    }

    @Operation(summary = "会话历史", description = "按时间正序返回本人会话内的消息")
    @GetMapping("/session/{sessionId}/messages")
    public RespModel<List<AssistantMessageVO>> history(
            @Parameter(description = "会话ID") @PathVariable String sessionId,
            @Parameter(description = "返回条数，默认 20，最大 100")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return RespModel.success(assistantService.history(sessionId, limit));
    }

    @Operation(summary = "我的会话分页", description = "按最近活跃倒序")
    @GetMapping("/sessions")
    public RespModel<Page<AssistantSessionVO>> sessions(
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int pageSize) {
        return RespModel.success(assistantService.pageSessions(pageNum, pageSize));
    }

    @Operation(summary = "删除会话", description = "删除本人会话及其全部消息")
    @DeleteMapping("/session/{sessionId}")
    public RespModel<Void> deleteSession(@Parameter(description = "会话ID") @PathVariable String sessionId) {
        assistantService.deleteSession(sessionId);
        return RespModel.success();
    }

    @Operation(summary = "快捷提问", description = "弹窗初始展示的推荐问题")
    @GetMapping("/suggestions")
    public RespModel<List<String>> suggestions() {
        return RespModel.success(assistantService.suggestions());
    }

    @Operation(summary = "能力清单", description = "当前模式（AGENT/RULE）、已注册工具、长期记忆与知识库规模")
    @GetMapping("/capabilities")
    public RespModel<AssistantCapabilityVO> capabilities() {
        return RespModel.success(assistantService.capabilities());
    }

    @Operation(summary = "会话轨迹", description = "按时间正序返回该会话落库的 Agent 工具调用轨迹")
    @GetMapping("/session/{sessionId}/trace")
    public RespModel<List<AgentStepVO>> trace(
            @Parameter(description = "会话ID") @PathVariable String sessionId,
            @Parameter(description = "返回条数，默认 50，最大 200")
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return RespModel.success(assistantService.trace(sessionId, limit));
    }

    @Operation(summary = "我的长期记忆", description = "列出当前用户被记住的称呼、偏好与备注")
    @GetMapping("/memories")
    public RespModel<List<AssistantMemoryVO>> memories(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return RespModel.success(memoryService.list(limit).stream().map(this::toMemoryVO).toList());
    }

    @Operation(summary = "删除长期记忆", description = "只能删除本人的记忆")
    @DeleteMapping("/memory/{id}")
    public RespModel<Void> deleteMemory(@Parameter(description = "记忆ID") @PathVariable Long id) {
        memoryService.delete(id);
        return RespModel.success();
    }

    private AssistantMemoryVO toMemoryVO(AssistantUserMemory memory) {
        AssistantMemoryVO vo = new AssistantMemoryVO();
        vo.setId(memory.getId());
        vo.setKey(memory.getMemoryKey());
        vo.setValue(memory.getMemoryValue());
        vo.setSource(memory.getSource());
        vo.setUpdateTime(memory.getUpdateTime());
        return vo;
    }
}
