package com.dbappsecurity.prd2api.constant;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.common.BizException;
import lombok.Getter;

/**
 * LLM 模型枚举：code 为对外暴露的模型编码，modelName 为真实调用模型名
 */
@Getter
public enum LlmModelEnum {

    DEFAULT("default", null, "跟随配置 prd2api.llm.model-name"),
    DEEPSEEK_CHAT("deepseek-chat", "deepseek-chat", "DeepSeek 通用对话模型"),
    DEEPSEEK_REASONER("deepseek-reasoner", "deepseek-reasoner", "DeepSeek 推理模型"),
    QWEN_PLUS("qwen-plus", "qwen-plus", "通义千问 Plus");

    private final String code;
    private final String modelName;
    private final String desc;

    LlmModelEnum(String code, String modelName, String desc) {
        this.code = code;
        this.modelName = modelName;
        this.desc = desc;
    }

    /**
     * 解析模型编码，未匹配则抛出业务异常；空编码返回 DEFAULT
     */
    public static LlmModelEnum resolve(String code) {
        if (StrUtil.isBlank(code)) {
            return DEFAULT;
        }
        for (LlmModelEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        throw new BizException(BizErrorCodeEnum.MODEL_NOT_SUPPORTED, "不支持的模型编码: " + code);
    }

    /**
     * 拿到可调用模型名，DEFAULT 交由配置决定
     */
    public String modelNameOrDefault(String defaultModelName) {
        return StrUtil.isBlank(modelName) ? defaultModelName : modelName;
    }
}
