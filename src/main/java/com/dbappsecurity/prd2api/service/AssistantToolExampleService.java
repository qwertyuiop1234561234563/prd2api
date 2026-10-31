package com.dbappsecurity.prd2api.service;

import java.util.List;

/**
 * 工具调用样例沉淀服务（Agent 自优化）
 * <p>
 * 解决的问题：工具定义里的参数格式要靠模型自己猜，模型容易给出不存在的字段或错误的值类型。
 * 这里从本账号历史“成功”轨迹里挖掘真实可用的参数格式，生成 few-shot 样例并注入系统提示词，
 * 让模型照着已验证的格式调用，而不是每次从零推理。全程规则化、零模型调用，
 * 样例统一来自已脱敏的轨迹，不会把敏感信息再带进提示词。
 */
public interface AssistantToolExampleService {

    /**
     * 生成注入提示词的样例块（带 Redis 缓存，同一用户 10 分钟内复用）
     */
    Block block();

    /**
     * 查看样例沉淀情况，便于核对覆盖了哪些工具
     *
     * @param days  挖掘窗口（天），1~90
     * @param limit 最多扫描轨迹条数，默认 3000，上限 5000
     */
    Report report(int days, int limit);

    /**
     * 提示词样例块：text 为空表示没有可用样例
     */
    record Block(String text, int examples) {

        public boolean empty() {
            return text == null || text.isEmpty() || examples <= 0;
        }
    }

    /**
     * 单条样例：参数与结果摘要均来自历史成功轨迹
     */
    record Example(String toolName, String arguments, String resultSummary, int successCount) {
    }

    /**
     * 样例沉淀报告：toolsCovered 为有样例的工具数，promptChars 为注入块字符数
     */
    record Report(int windowDays, int scannedTraces, int toolsCovered, int examples,
                  int promptChars, List<Example> items, List<String> notes) {
    }
}
