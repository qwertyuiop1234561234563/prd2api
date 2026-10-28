package com.dbappsecurity.prd2api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.annotation.Audit;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.dto.CouponClaimQueryDTO;
import com.dbappsecurity.prd2api.dto.CouponCreateDTO;
import com.dbappsecurity.prd2api.dto.CouponQueryDTO;
import com.dbappsecurity.prd2api.service.CouponService;
import com.dbappsecurity.prd2api.vo.CouponVO;
import com.dbappsecurity.prd2api.vo.UserCouponVO;
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

/**
 * 管理端优惠券接口：发放、分页、下架、领取记录
 * <p>
 * 需 ADMIN 角色（登录态角色或 X-User-Role Header）
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/coupon")
@Tag(name = "管理端-优惠券", description = "需要 ADMIN 角色")
public class AdminCouponController {

    private final CouponService couponService;

    @Audit("发放优惠券")
    @Operation(summary = "发放优惠券", description = "按套餐发放限量优惠券，创建后立即进入可领取状态")
    @PostMapping
    public RespModel<CouponVO> create(@Valid @RequestBody CouponCreateDTO dto) {
        return RespModel.success(couponService.create(dto));
    }

    @Operation(summary = "优惠券分页", description = "按状态、套餐、名称过滤")
    @GetMapping("/page")
    public RespModel<Page<CouponVO>> page(@ParameterObject @Valid CouponQueryDTO dto) {
        return RespModel.success(couponService.pageCoupons(dto));
    }

    @Audit("优惠券下架")
    @Operation(summary = "优惠券下架", description = "停止新领取，已领取的券仍可正常使用")
    @DeleteMapping("/{couponId}")
    public RespModel<Void> offline(
            @Parameter(description = "优惠券ID") @PathVariable Long couponId) {
        couponService.offline(couponId);
        return RespModel.success();
    }

    @Operation(summary = "领取记录", description = "查看某批优惠券的领取与使用情况")
    @GetMapping("/{couponId}/claims")
    public RespModel<Page<UserCouponVO>> claims(
            @Parameter(description = "优惠券ID") @PathVariable Long couponId,
            @ParameterObject @Valid CouponClaimQueryDTO dto) {
        return RespModel.success(couponService.pageClaims(couponId, dto));
    }
}
