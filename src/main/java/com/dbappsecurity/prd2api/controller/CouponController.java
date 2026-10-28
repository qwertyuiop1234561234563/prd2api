package com.dbappsecurity.prd2api.controller;

import com.dbappsecurity.prd2api.annotation.Audit;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.service.CouponService;
import com.dbappsecurity.prd2api.vo.CouponVO;
import com.dbappsecurity.prd2api.vo.MyVipVO;
import com.dbappsecurity.prd2api.vo.UserCouponVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 优惠券用户端接口：可领列表、领取、我的券、核销开通 VIP
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/coupon")
@Tag(name = "优惠券", description = "限量优惠券领取与核销开通 VIP")
public class CouponController {

    private final CouponService couponService;

    @Operation(summary = "可领取优惠券", description = "仅返回领取中的批次，包含当前用户已领数量")
    @GetMapping("/available")
    public RespModel<List<CouponVO>> available() {
        return RespModel.success(couponService.listAvailable());
    }

    @Audit("领取优惠券")
    @Operation(summary = "领取优惠券", description = "Redis Lua 原子扣减库存，每人限领校验")
    @PostMapping("/{couponId}/claim")
    public RespModel<UserCouponVO> claim(
            @Parameter(description = "优惠券ID") @PathVariable @NotNull(message = "couponId 不能为空") Long couponId) {
        return RespModel.success(couponService.claim(couponId));
    }

    @Operation(summary = "我的优惠券", description = "按领取时间倒序，含 UNUSED / USED / EXPIRED 状态")
    @GetMapping("/my")
    public RespModel<List<UserCouponVO>> my() {
        return RespModel.success(couponService.myCoupons());
    }

    @Audit("使用优惠券")
    @Operation(summary = "使用优惠券", description = "原子核销后立即开通对应 VIP 套餐")
    @PostMapping("/{userCouponId}/use")
    public RespModel<MyVipVO> use(
            @Parameter(description = "用户优惠券主键") @PathVariable @NotNull(message = "userCouponId 不能为空")
            Long userCouponId) {
        return RespModel.success(couponService.use(userCouponId));
    }
}
