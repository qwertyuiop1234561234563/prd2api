package com.dbappsecurity.prd2api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.annotation.Audit;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.dto.AgentChatDTO;
import com.dbappsecurity.prd2api.dto.RetryTaskDTO;
import com.dbappsecurity.prd2api.dto.TaskQueryDTO;
import com.dbappsecurity.prd2api.dto.UploadFileDTO;
import com.dbappsecurity.prd2api.service.TaskService;
import com.dbappsecurity.prd2api.service.TaskStreamService;
import com.dbappsecurity.prd2api.vo.ReportExportVO;
import com.dbappsecurity.prd2api.vo.ReviewIssueVO;
import com.dbappsecurity.prd2api.vo.TaskDiffVO;
import com.dbappsecurity.prd2api.vo.TaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 任务接口：创建、查询、重试、追问、导出、删除
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/task")
@Tag(name = "任务管理", description = "PRD 解析任务全生命周期")
public class TaskController {

    private final TaskService taskService;
    private final TaskStreamService taskStreamService;

    @Audit("任务创建")
    @Operation(summary = "创建解析任务", description = "上传文件后创建任务，异步执行 4 阶段流水线；相同文件+模型+分块策略命中缓存时直接返回成功任务")
    @PostMapping("/create")
    public RespModel<TaskVO> create(@Valid @RequestBody UploadFileDTO dto) {
        return RespModel.success(taskService.createTask(dto));
    }

    @Operation(summary = "查询任务详情", description = "返回进度、状态与 Markdown 评审报告")
    @GetMapping("/{taskId}")
    public RespModel<TaskVO> detail(@Parameter(description = "任务ID") @PathVariable String taskId) {
        return RespModel.success(taskService.getTask(taskId));
    }

    @Operation(summary = "分页查询任务", description = "管理员可传 tenantId 查看指定租户")
    @GetMapping("/page")
    public RespModel<Page<TaskVO>> page(@ParameterObject @Valid TaskQueryDTO dto) {
        return RespModel.success(taskService.pageTasks(dto));
    }

    @Audit("任务重试")
    @Operation(summary = "重试失败任务")
    @PostMapping("/retry")
    public RespModel<Void> retry(@Valid @RequestBody RetryTaskDTO dto) {
        taskService.retryTask(dto);
        return RespModel.success();
    }

    @Audit("任务取消")
    @Operation(summary = "取消任务", description = "写入 Redis 取消标记，流水线在当前阶段结束后中断，状态置为 CANCELED")
    @PostMapping("/{taskId}/cancel")
    public RespModel<Void> cancel(@Parameter(description = "任务ID") @PathVariable String taskId) {
        taskService.cancelTask(taskId);
        return RespModel.success();
    }

    @Operation(summary = "任务进度流式订阅",
            description = "SSE 长连接，每秒推送一次任务进度，任务结束（成功/失败/取消）后自动关闭，最长 10 分钟")
    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Parameter(description = "任务ID") @PathVariable String taskId) {
        return taskStreamService.stream(taskId);
    }

    @Operation(summary = "报告版本对比",
            description = "同一文档的两个成功版本按行对比，返回 unified diff 文本与增删行数")
    @GetMapping("/{taskId}/diff")
    public RespModel<TaskDiffVO> diff(
            @Parameter(description = "基线任务ID") @PathVariable String taskId,
            @Parameter(description = "对比任务ID") @RequestParam String otherTaskId) {
        return RespModel.success(taskService.diffReport(taskId, otherTaskId));
    }

    @Operation(summary = "结构化待确认清单",
            description = "从报告 Markdown 中抽取 ✅确定 / ❓待确认 / ⚠️冲突 条目，供评审勾选")
    @GetMapping("/{taskId}/issues")
    public RespModel<List<ReviewIssueVO>> issues(@Parameter(description = "任务ID") @PathVariable String taskId) {
        return RespModel.success(taskService.extractIssues(taskId));
    }

    @Audit("追加追问")
    @Operation(summary = "追加追问", description = "基于已有报告 + RAG 召回回答追问，并将问答追加到报告末尾")
    @PostMapping("/chat")
    public RespModel<String> chat(@Valid @RequestBody AgentChatDTO dto) {
        return RespModel.success(taskService.chat(dto));
    }

    @Audit("报告导出")
    @Operation(summary = "导出评审报告", description = "支持 md / html / docx，返回一次性下载凭证")
    @GetMapping("/{taskId}/export")
    public RespModel<ReportExportVO> export(
            @Parameter(description = "任务ID") @PathVariable String taskId,
            @Parameter(description = "导出格式 md/html/docx") @RequestParam(defaultValue = "md") String format) {
        return RespModel.success(taskService.exportReport(taskId, format));
    }

    @Operation(summary = "下载导出文件", description = "使用导出接口返回的 exportToken 下载，无需额外 Header 鉴权")
    @GetMapping("/export/download")
    public ResponseEntity<Resource> download(
            @Parameter(description = "一次性导出凭证") @RequestParam String token) {
        TaskService.ExportFile exportFile = taskService.loadExportFile(token);
        String encodedName = URLEncoder.encode(exportFile.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(exportFile.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(exportFile.resource());
    }

    @Audit("任务删除")
    @Operation(summary = "删除任务", description = "逻辑删除任务并清理对应向量索引")
    @DeleteMapping("/{taskId}")
    public RespModel<Void> delete(@Parameter(description = "任务ID") @PathVariable String taskId) {
        taskService.deleteTask(taskId);
        return RespModel.success();
    }
}
