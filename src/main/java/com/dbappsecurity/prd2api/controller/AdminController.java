package com.dbappsecurity.prd2api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.dto.TaskQueryDTO;
import com.dbappsecurity.prd2api.dto.TenantQuotaDTO;
import com.dbappsecurity.prd2api.service.FileService;
import com.dbappsecurity.prd2api.service.TaskService;
import com.dbappsecurity.prd2api.service.TenantQuotaService;
import com.dbappsecurity.prd2api.vo.TaskVO;
import com.dbappsecurity.prd2api.vo.TenantQuotaVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端接口：全租户任务、配额管理、租户数据清理、手工清理
 * <p>
 * 需在网关 Header 中携带 X-User-Role: ADMIN
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
@Tag(name = "管理端", description = "需要 ADMIN 角色，Header: X-User-Role=ADMIN")
public class AdminController {

    private final TaskService taskService;
    private final TenantQuotaService tenantQuotaService;
    private final FileService fileService;

    @Operation(summary = "全租户任务分页", description = "按 tenantId / 状态过滤全部租户任务")
    @GetMapping("/task/page")
    public RespModel<Page<TaskVO>> pageTasks(@ParameterObject @Valid TaskQueryDTO dto) {
        return RespModel.success(taskService.pageTasks(dto));
    }

    @Operation(summary = "查询租户配额")
    @GetMapping("/quota/{tenantId}")
    public RespModel<TenantQuotaVO> getQuota(
            @Parameter(description = "租户ID") @PathVariable String tenantId) {
        return RespModel.success(tenantQuotaService.getQuota(tenantId));
    }

    @Operation(summary = "配置租户配额", description = "存在则更新限额，不存在则初始化")
    @PostMapping("/quota")
    public RespModel<TenantQuotaVO> saveQuota(@Valid @RequestBody TenantQuotaDTO dto) {
        return RespModel.success(tenantQuotaService.saveQuota(dto));
    }

    @Operation(summary = "清理租户数据", description = "逻辑删除该租户任务、清理向量集合与本地文件，并删除配额记录（恢复默认限额）")
    @DeleteMapping("/tenant/{tenantId}")
    public RespModel<Map<String, Object>> purgeTenant(
            @Parameter(description = "租户ID") @PathVariable String tenantId) {
        int taskCount = taskService.purgeTenant(tenantId);
        tenantQuotaService.deleteQuota(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenantId);
        result.put("taskCount", taskCount);
        return RespModel.success(result);
    }

    @Operation(summary = "手工触发清理", description = "清理过期文件、过期报告缓存与过期导出文件")
    @PostMapping("/clean")
    public RespModel<Map<String, Integer>> clean() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("expiredFiles", fileService.cleanExpired());
        result.put("expiredCaches", fileService.cleanExpiredCache());
        result.put("expiredExports", fileService.cleanExpiredExports());
        return RespModel.success(result);
    }
}
