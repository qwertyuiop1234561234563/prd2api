package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.service.AssistantPromptExperimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 系统提示词 A/B 实验实现：SHA-256 稳定分桶，零随机数
 * <p>
 * 用 userKey + 实验名做哈希，取前 4 字节转成 0~99 的桶号；
 * 桶号小于配置的实验流量百分比即进入 B 组，否则留在 A 组。
 * 只要实验名不变，同一用户每次都会落在同一组，避免体验来回跳变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantPromptExperimentServiceImpl implements AssistantPromptExperimentService {

    /** B 组提示词：先结论、后依据、限行数，降低啰嗦与重复工具输出 */
    private static final String VARIANT_B_SUFFIX = """

            【回答风格】先给结论，再给必要依据；控制在 6 行以内；不要重复工具原始输出与完整 JSON。""";
    private static final String VERSION_A = "v1-a";
    private static final String VERSION_B = "v2-b";

    private final Prd2ApiProperties properties;

    @Override
    public Assignment assign(String userKey) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        String experiment = config == null ? "assistant-system-v2" : config.getPromptAbExperiment();
        if (config == null || !config.isPromptAbEnabled()) {
            return new Assignment(experiment, "A", -1, VERSION_A, "", "A/B 实验已关闭，使用对照版本");
        }
        if (StrUtil.isBlank(userKey)) {
            return new Assignment(experiment, "A", -1, VERSION_A, "", "缺少用户标识，固定使用对照版本");
        }
        int percent = Math.max(0, Math.min(100, config.getPromptAbPercent()));
        int bucket = bucket(userKey, experiment);
        boolean treatment = bucket < percent;
        Assignment assignment = treatment
                ? new Assignment(experiment, "B", bucket, VERSION_B, VARIANT_B_SUFFIX,
                        "桶号 " + bucket + " < 实验流量 " + percent + "%，进入实验组")
                : new Assignment(experiment, "A", bucket, VERSION_A, "",
                        "桶号 " + bucket + " >= 实验流量 " + percent + "%，保持对照组");
        log.info("提示词实验 experiment={} user={} variant={} bucket={}",
                experiment, StrUtil.maxLength(userKey, 24), assignment.variant(), bucket);
        return assignment;
    }

    /**
     * 稳定分桶：前 4 字节按无符号整数取模 100
     */
    private int bucket(String userKey, String experiment) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((userKey + "|" + experiment).getBytes(StandardCharsets.UTF_8));
            int value = ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16)
                    | ((hash[2] & 0xFF) << 8) | (hash[3] & 0xFF);
            return Math.floorMod(value, 100);
        } catch (Exception e) {
            // 哈希不可用时退回对照组，宁可不上实验也不让用户随机抖动
            log.warn("提示词实验分桶失败，回落对照组 msg={}", e.getMessage());
            return 100;
        }
    }
}
