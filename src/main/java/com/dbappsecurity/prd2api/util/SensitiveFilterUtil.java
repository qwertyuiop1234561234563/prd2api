package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 敏感词过滤工具（可开关）
 * <p>
 * 采用配置化词库 + 忽略大小写的包含匹配，命中的词做掩码处理后再送 LLM。
 */
@Component
@RequiredArgsConstructor
public class SensitiveFilterUtil {

    private final Prd2ApiProperties properties;

    public boolean enabled() {
        return properties.getSensitive().isEnable()
                && !properties.getSensitive().getWords().isEmpty();
    }

    /**
     * 返回命中的敏感词
     */
    public List<String> find(String text) {
        List<String> hits = new ArrayList<>();
        if (!enabled() || StrUtil.isBlank(text)) {
            return hits;
        }
        String lower = text.toLowerCase();
        for (String word : properties.getSensitive().getWords()) {
            if (StrUtil.isNotBlank(word) && lower.contains(word.toLowerCase())) {
                hits.add(word);
            }
        }
        return hits;
    }

    /**
     * 敏感词掩码
     */
    public String mask(String text) {
        if (!enabled() || StrUtil.isBlank(text)) {
            return text;
        }
        String result = text;
        for (String word : properties.getSensitive().getWords()) {
            if (StrUtil.isNotBlank(word)) {
                result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(word), "***");
            }
        }
        return result;
    }
}
