package com.dbappsecurity.prd2api.controller;

import com.dbappsecurity.prd2api.annotation.Audit;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.dto.RedeemDTO;
import com.dbappsecurity.prd2api.service.QuotaResolver;
import com.dbappsecurity.prd2api.service.VipService;
import com.dbappsecurity.prd2api.vo.MyVipVO;
import com.dbappsecurity.prd2api.vo.QuotaInfoVO;
import com.dbappsecurity.prd2api.vo.VipPlanVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * VIP 用户端接口：套餐列表、我的 VIP、兑换码、当前额度
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/vip")
@Tag(name = "VIP", description = "用户级套餐额度，未开通回退租户配额")
public class VipController {

    private final VipService vipService;
    private final QuotaResolver quotaResolver;

    @Operation(summary = "套餐列表", description = "仅返回上架套餐，含免费套餐用于展示")
    @GetMapping("/plans")
    public RespModel<List<VipPlanVO>> plans() {
        return RespModel.success(vipService.listPlans());
    }

    @Operation(summary = "我的 VIP", description = "套餐信息、到期时间、剩余天数与今日用量")
    @GetMapping("/my")
    public RespModel<MyVipVO> my() {
        return RespModel.success(vipService.myVip());
    }

    @Audit("兑换码兑换")
    @Operation(summary = "兑换码兑换", description = "原子抢占兑换码，成功后立即生效，同套餐自动续期")
    @PostMapping("/redeem")
    public RespModel<MyVipVO> redeem(@Valid @RequestBody RedeemDTO dto) {
        return RespModel.success(vipService.redeem(dto.getCode()));
    }

    @Operation(summary = "当前生效额度", description = "VIP 生效返回套餐额度，否则返回所属租户配额")
    @GetMapping("/quota")
    public RespModel<QuotaInfoVO> quota() {
        return RespModel.success(quotaResolver.currentQuota());
    }
}
