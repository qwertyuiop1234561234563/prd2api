package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * 计划校验服务：模型产出的计划不可信，执行前做可执行性检查并自动修复
 * <p>
 * 只做确定性规则校验，不额外调用模型：工具名必须已注册、同一工具不重复、
 * 汇总步骤必须在最后、步数不超过上限。修复后仍不可执行的计划直接丢弃，回落 ReAct。
 */
public interface AssistantPlanValidatorService {

    /**
     * 校验并修复计划
     *
     * @param plan 模型产出的原始计划
     * @return 问题清单与修复后的计划；修复后不可执行时 repaired 为 null
     */
    Repair validate(AssistantPlannerService.Plan plan);

    /**
     * 校验结果
     *
     * @param valid    原计划是否无需修复即可执行
     * @param issues   发现的问题（用于审计与日志）
     * @param repaired 修复后的计划，null 表示不可执行
     */
    record Repair(boolean valid, List<String> issues, AssistantPlannerService.Plan repaired) {
    }
}
