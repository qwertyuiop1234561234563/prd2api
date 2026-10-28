package com.dbappsecurity.prd2api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.dto.AssistantChatDTO;
import com.dbappsecurity.prd2api.service.AssistantService;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 桌面助手接口：页面小人的对话框后端
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/assistant")
@Tag(name = "桌面助手", description = "页面小人问答：快捷查询、任务操作与平台答疑")
public class AssistantController {

    private final AssistantService assistantService;

    @Operation(summary = "提问",
            description = "识别意图：任务/额度/文件/会员等操作类问题直接查询执行，其余走大模型，"
                    + "未配置模型时回落内置知识库；返回文本与前端动作（卡片或跳转）")
    @PostMapping("/chat")
    public RespModel<AssistantReplyVO> chat(@Valid @RequestBody AssistantChatDTO dto) {
        return RespModel.success(assistantService.chat(dto));
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
}
