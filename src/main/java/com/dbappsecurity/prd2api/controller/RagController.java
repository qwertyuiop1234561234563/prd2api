package com.dbappsecurity.prd2api.controller;

import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.service.RagService;
import com.dbappsecurity.prd2api.service.TaskService;
import com.dbappsecurity.prd2api.vo.RagChunkVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 接口：召回调试、向量库状态
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag")
@Tag(name = "RAG 检索", description = "任务级向量召回与向量库状态")
public class RagController {

    private final RagService ragService;
    private final TaskService taskService;
    private final Prd2ApiProperties properties;

    @Operation(summary = "召回片段", description = "在指定任务的分块索引中检索 TopK 片段，向量库不可用时自动降级本地召回")
    @GetMapping("/search")
    public RespModel<List<RagChunkVO>> search(
            @Parameter(description = "任务ID") @RequestParam String taskId,
            @Parameter(description = "查询语句") @RequestParam @NotBlank(message = "query 不能为空")
            @Size(max = 500, message = "query 长度不能超过 500") String query,
            @Parameter(description = "召回条数，默认取配置值") @RequestParam(required = false) Integer topK) {
        int k = topK == null || topK <= 0 ? properties.getRag().getTopK() : Math.min(topK, 50);
        // 租户隔离：先校验任务归属，避免跨租户读取分块
        taskService.checkTaskAccess(taskId);
        return RespModel.success(ragService.retrieve(taskId, query, k));
    }

    @Operation(summary = "RAG 状态", description = "返回 Chroma 连通性与当前配置")
    @GetMapping("/status")
    public RespModel<Map<String, Object>> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("ragEnabled", properties.getRag().isEnable());
        status.put("chromaUrl", properties.getRag().getChromaUrl());
        status.put("chromaAvailable", ragService.chromaAvailable());
        status.put("topK", properties.getRag().getTopK());
        status.put("chunkStrategy", properties.getChunk().getStrategy());
        return RespModel.success(status);
    }
}
