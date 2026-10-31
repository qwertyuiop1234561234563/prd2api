package com.dbappsecurity.prd2api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.annotation.Audit;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.dto.KnowledgeDocQueryDTO;
import com.dbappsecurity.prd2api.service.AssistantKnowledgeService;
import com.dbappsecurity.prd2api.util.ChunkUtil;
import com.dbappsecurity.prd2api.vo.KnowledgeChunkVO;
import com.dbappsecurity.prd2api.vo.KnowledgeDocVO;
import com.dbappsecurity.prd2api.vo.RagChunkVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 助手知识库接口：离线文档上传、分段预览、启停、重建与检索测试
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge")
@Tag(name = "助手知识库", description = "租户级离线知识文档上传与索引管理")
public class KnowledgeController {

    private final AssistantKnowledgeService knowledgeService;

    @Audit("知识库上传")
    @Operation(summary = "上传知识文档",
            description = "支持 md/txt/docx/pdf，租户内 md5 去重；上传后异步解析切分与向量化，"
                    + "返回状态 PENDING，可通过分页接口轮询进度")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RespModel<KnowledgeDocVO> upload(
            @Parameter(description = "知识文档") @RequestPart("file") MultipartFile file,
            @Parameter(description = "切分策略 fixed / semantic，默认取全局配置")
            @RequestParam(required = false) String strategy) {
        return RespModel.success(knowledgeService.upload(file, normalizeStrategy(strategy)));
    }

    @Operation(summary = "知识文档分页", description = "普通用户仅看本租户，管理员可传 tenantId 查看指定租户")
    @GetMapping("/page")
    public RespModel<Page<KnowledgeDocVO>> page(@ParameterObject @Valid KnowledgeDocQueryDTO dto) {
        return RespModel.success(knowledgeService.page(dto));
    }

    @Operation(summary = "分段预览", description = "分页查看文档切分结果，便于核对引用来源")
    @GetMapping("/{docId}/chunks")
    public RespModel<Page<KnowledgeChunkVO>> chunks(
            @Parameter(description = "文档ID") @PathVariable String docId,
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize) {
        return RespModel.success(knowledgeService.pageChunks(docId, pageNum, pageSize));
    }

    @Audit("知识库启停")
    @Operation(summary = "启用/停用检索", description = "停用会移除向量；启用时若索引缺失会自动重建")
    @PutMapping("/{docId}/enabled")
    public RespModel<Void> setEnabled(
            @Parameter(description = "文档ID") @PathVariable String docId,
            @Parameter(description = "true 启用 / false 停用") @RequestParam boolean enabled) {
        knowledgeService.setEnabled(docId, enabled);
        return RespModel.success();
    }

    @Audit("知识库重建索引")
    @Operation(summary = "重建索引", description = "重新解析、切分与向量化；进行中的文档不允许重复触发")
    @PostMapping("/{docId}/rebuild")
    public RespModel<Void> rebuild(@Parameter(description = "文档ID") @PathVariable String docId) {
        knowledgeService.rebuild(docId);
        return RespModel.success();
    }

    @Audit("知识库删除")
    @Operation(summary = "删除知识文档", description = "同时清理分段、向量与本地文件")
    @DeleteMapping("/{docId}")
    public RespModel<Void> delete(@Parameter(description = "文档ID") @PathVariable String docId) {
        knowledgeService.delete(docId);
        return RespModel.success();
    }

    @Operation(summary = "知识库概览", description = "按状态计数、分段总数与向量可用性")
    @GetMapping("/stats")
    public RespModel<Map<String, Object>> stats() {
        return RespModel.success(knowledgeService.stats());
    }

    @Operation(summary = "检索测试", description = "与 Agent 使用同一检索链路，返回内置语料与上传文档的合并结果")
    @GetMapping("/search")
    public RespModel<List<RagChunkVO>> search(
            @Parameter(description = "检索语句") @RequestParam @NotBlank(message = "query 不能为空")
            @Size(max = 500, message = "query 长度不能超过 500") String query,
            @Parameter(description = "召回条数，默认 4，最大 20")
            @RequestParam(required = false) Integer topK) {
        int k = topK == null || topK <= 0 ? 4 : Math.min(topK, 20);
        return RespModel.success(knowledgeService.search(query, k));
    }

    private String normalizeStrategy(String strategy) {
        if (StrUtil.isBlank(strategy)) {
            return null;
        }
        if (ChunkUtil.STRATEGY_FIXED.equalsIgnoreCase(strategy)) {
            return ChunkUtil.STRATEGY_FIXED;
        }
        if (ChunkUtil.STRATEGY_SEMANTIC.equalsIgnoreCase(strategy)) {
            return ChunkUtil.STRATEGY_SEMANTIC;
        }
        throw new BizException(BizErrorCodeEnum.PARAM_INVALID, "strategy 仅支持 fixed / semantic");
    }
}
