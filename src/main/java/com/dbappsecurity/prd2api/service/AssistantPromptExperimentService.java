package com.dbappsecurity.prd2api.service;

/**
 * 系统提示词 A/B 实验：按用户稳定分桶，同一用户始终命中同一变体
 * <p>
 * 目的：提示词改版不再“一刀切”上线，而是先小流量对照，
 * 分桶结果写入日志与指标，便于按用户维度对比答复质量/成本。
 */
public interface AssistantPromptExperimentService {

    /**
     * 为用户分配实验变体
     *
     * @param userKey 租户内用户标识，为空时固定对照组
     */
    Assignment assign(String userKey);

    /**
     * 分桶结果
     *
     * @param experiment    实验名
     * @param variant       A（对照）或 B（实验）
     * @param bucket        0~99 的稳定桶号，未参与实验时为 -1
     * @param promptVersion 提示词版本号
     * @param suffix        需要追加到系统提示词的实验指令，对照组为空
     * @param reason        分配原因，便于排查“为什么我是 B”
     */
    record Assignment(String experiment, String variant, int bucket,
                      String promptVersion, String suffix, String reason) {
    }
}
