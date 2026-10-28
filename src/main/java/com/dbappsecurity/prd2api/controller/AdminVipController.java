package com.dbappsecurity.prd2api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.annotation.Audit;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.dto.AdminUserCreateDTO;
import com.dbappsecurity.prd2api.dto.RedeemCodeBatchDTO;
import com.dbappsecurity.prd2api.dto.RedeemCodeQueryDTO;
import com.dbappsecurity.prd2api.dto.UserQueryDTO;
import com.dbappsecurity.prd2api.dto.VipGrantDTO;
import com.dbappsecurity.prd2api.dto.VipPlanDTO;
import com.dbappsecurity.prd2api.service.AuthService;
import com.dbappsecurity.prd2api.service.VipService;
import com.dbappsecurity.prd2api.vo.RedeemCodeVO;
import com.dbappsecurity.prd2api.vo.UserVO;
import com.dbappsecurity.prd2api.vo.VipPlanVO;
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

import java.util.List;

/**
 * 管理端 VIP 接口：开通、用户分页、套餐配置、兑换码管理
 * <p>
 * 需 ADMIN 角色（登录态角色或 X-User-Role Header）
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/vip")
@Tag(name = "管理端-VIP", description = "需要 ADMIN 角色")
public class AdminVipController {

    private final VipService vipService;
    private final AuthService authService;

    @Audit("创建用户")
    @Operation(summary = "创建用户", description = "可指定 ADMIN/USER 角色，用于初始化管理员账号")
    @PostMapping("/user")
    public RespModel<UserVO> createUser(@Valid @RequestBody AdminUserCreateDTO dto) {
        return RespModel.success(authService.createUser(dto));
    }

    @Operation(summary = "用户分页", description = "按租户、账号、套餐、VIP 生效状态过滤")
    @GetMapping("/user/page")
    public RespModel<Page<UserVO>> pageUsers(@ParameterObject @Valid UserQueryDTO dto) {
        return RespModel.success(vipService.pageUsers(dto));
    }

    @Audit("开通VIP")
    @Operation(summary = "手动开通 VIP", description = "同套餐续期累加，切换套餐从当前时间重新计算")
    @PostMapping("/grant")
    public RespModel<UserVO> grant(@Valid @RequestBody VipGrantDTO dto) {
        return RespModel.success(vipService.grant(dto));
    }

    @Operation(summary = "全部套餐", description = "包含已下架套餐，供管理端配置")
    @GetMapping("/plan/all")
    public RespModel<List<VipPlanVO>> allPlans() {
        return RespModel.success(vipService.listAllPlans());
    }

    @Audit("套餐配置")
    @Operation(summary = "新增或更新套餐", description = "planCode 不存在则新增，存在则更新")
    @PostMapping("/plan")
    public RespModel<VipPlanVO> savePlan(@Valid @RequestBody VipPlanDTO dto) {
        return RespModel.success(vipService.savePlan(dto));
    }

    @Audit("套餐下架")
    @Operation(summary = "套餐下架", description = "软下架，已开通用户不受影响；FREE 套餐不允许下架")
    @DeleteMapping("/plan/{planCode}")
    public RespModel<Void> offlinePlan(
            @Parameter(description = "套餐编码") @PathVariable String planCode) {
        vipService.offlinePlan(planCode);
        return RespModel.success();
    }

    @Audit("生成兑换码")
    @Operation(summary = "批量生成兑换码", description = "单次最多 500 个，返回本次生成的完整兑换码")
    @PostMapping("/redeem-code/batch")
    public RespModel<List<RedeemCodeVO>> generateCodes(@Valid @RequestBody RedeemCodeBatchDTO dto) {
        return RespModel.success(vipService.generateCodes(dto));
    }

    @Operation(summary = "兑换码分页", description = "按套餐、状态、批次号过滤")
    @GetMapping("/redeem-code/page")
    public RespModel<Page<RedeemCodeVO>> pageCodes(@ParameterObject @Valid RedeemCodeQueryDTO dto) {
        return RespModel.success(vipService.pageCodes(dto));
    }
}
