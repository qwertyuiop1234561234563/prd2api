package com.dbappsecurity.prd2api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.dto.RedeemCodeBatchDTO;
import com.dbappsecurity.prd2api.dto.RedeemCodeQueryDTO;
import com.dbappsecurity.prd2api.dto.UserQueryDTO;
import com.dbappsecurity.prd2api.dto.VipGrantDTO;
import com.dbappsecurity.prd2api.dto.VipPlanDTO;
import com.dbappsecurity.prd2api.vo.MyVipVO;
import com.dbappsecurity.prd2api.vo.RedeemCodeVO;
import com.dbappsecurity.prd2api.vo.UserVO;
import com.dbappsecurity.prd2api.vo.VipPlanVO;

import java.util.List;

/**
 * VIP 服务：套餐、兑换码、开通记录、过期降级
 */
public interface VipService {

    /**
     * 上架套餐列表（用户端）
     */
    List<VipPlanVO> listPlans();

    /**
     * 当前用户 VIP 信息与今日用量
     */
    MyVipVO myVip();

    /**
     * 兑换码自助兑换
     */
    MyVipVO redeem(String code);

    /**
     * 用户分页（管理员）
     */
    Page<UserVO> pageUsers(UserQueryDTO dto);

    /**
     * 管理员手动开通 VIP
     */
    UserVO grant(VipGrantDTO dto);

    /**
     * 全部套餐（管理员）
     */
    List<VipPlanVO> listAllPlans();

    /**
     * 新增或更新套餐（管理员）
     */
    VipPlanVO savePlan(VipPlanDTO dto);

    /**
     * 套餐下架（管理员）
     */
    void offlinePlan(String planCode);

    /**
     * 批量生成兑换码（管理员）
     */
    List<RedeemCodeVO> generateCodes(RedeemCodeBatchDTO dto);

    /**
     * 兑换码分页（管理员）
     */
    Page<RedeemCodeVO> pageCodes(RedeemCodeQueryDTO dto);

    /**
     * 定时任务：清理过期 VIP 标记与过期兑换码，返回处理条数
     */
    int expireOverdue();
}
