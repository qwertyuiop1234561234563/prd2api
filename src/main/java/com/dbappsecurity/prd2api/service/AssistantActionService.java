package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.constant.AssistantIntentEnum;

/**
 * 桌面助手动作执行
 * <p>
 * 负责把识别出的意图转成真实的系统查询/操作（全部在租户与本人权限范围内），
 * 并生成可直接展示的回复与前端动作描述。
 */
public interface AssistantActionService {

    /**
     * 该意图是否由动作服务直接处理（无需调用大模型）
     */
    boolean executable(AssistantIntentEnum intent);

    /**
     * 执行意图
     */
    ActionResult execute(AssistantIntentEnum intent, String message);

    /**
     * 动作执行结果
     *
     * @param reply        展示文本
     * @param actionType   动作类型，见 AssistantActionType
     * @param actionLabel  按钮文案
     * @param actionTarget 跳转目标
     * @param data         卡片数据
     */
    record ActionResult(String reply, String actionType, String actionLabel,
                        String actionTarget, Object data) {
    }
}
