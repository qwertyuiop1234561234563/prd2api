package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;

/**
 * 上下文压缩工具：超长文档做首尾保留式裁剪，防止超出 LLM 上下文窗口
 */
public final class ContextCompressUtil {

    private static final double HEAD_RATIO = 0.6D;
    private static final double TAIL_RATIO = 0.3D;

    private ContextCompressUtil() {
    }

    public static boolean exceeds(String text, int maxChars) {
        return StrUtil.length(text) > maxChars;
    }

    /**
     * 首 60% + 尾 30% 保留，中间以压缩标记替代
     */
    public static String compress(String text, int maxChars) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int head = (int) (maxChars * HEAD_RATIO);
        int tail = (int) (maxChars * TAIL_RATIO);
        int omitted = text.length() - head - tail;
        return text.substring(0, head)
                + "\n\n【⚠️文档过长，已压缩上下文：省略约 " + omitted + " 字符，被省略部分需人工确认】\n\n"
                + text.substring(text.length() - tail);
    }
}
