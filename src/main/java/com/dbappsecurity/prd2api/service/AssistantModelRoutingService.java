package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * 模型路由服务：按问题复杂度选择模型档位
 * <p>
 * 固定用同一个模型既浪费（简单问答也走强模型）又不稳定（复杂任务走小模型容易漏步骤）。
 * 这里用确定性规则给问题打分并映射到模型档位，不额外消耗 token；
 * 打分依据可解释（长度、子问题数、复合指令、推理诉求、涉及工具数），便于调参与复盘。
 */
public interface AssistantModelRoutingService {

    /**
     * 根据问题文本给出路由决策
     */
    Route route(String message);

    /**
     * 路由结果
     *
     * @param level     档位：SIMPLE / STANDARD / COMPLEX
     * @param score     复杂度得分（0-100）
     * @param modelCode 选中的模型编码（default / deepseek-chat / ...）
     * @param factors   得分构成，便于解释“为什么走这个模型”
     */
    record Route(String level, int score, String modelCode, List<String> factors) {
    }
}
