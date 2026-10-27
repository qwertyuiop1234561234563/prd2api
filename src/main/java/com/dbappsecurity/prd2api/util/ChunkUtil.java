package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块工具：语义分块 / 固定长度分块
 */
public final class ChunkUtil {

    public static final String STRATEGY_FIXED = "fixed";
    public static final String STRATEGY_SEMANTIC = "semantic";

    private static final String PARA_REGEX = "\\n\\s*\\n";

    private ChunkUtil() {
    }

    /**
     * 按策略分块
     *
     * @param text      原始文本
     * @param strategy  fixed / semantic
     * @param fixedSize 单块最大字符数
     * @param overlap   固定分块重叠字符数
     */
    public static List<String> chunk(String text, String strategy, int fixedSize, int overlap) {
        if (StrUtil.isBlank(text)) {
            return new ArrayList<>();
        }
        String normalized = normalize(text);
        int size = fixedSize <= 0 ? 800 : fixedSize;
        if (STRATEGY_FIXED.equalsIgnoreCase(strategy)) {
            return fixedSplit(normalized, size, Math.max(0, overlap));
        }
        return semanticSplit(normalized, size);
    }

    public static String md5(String chunk) {
        return DigestUtil.md5Hex(chunk);
    }

    public static String normalize(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    /**
     * 固定长度分块，带 overlap 重叠，尽量在标点处断开
     */
    private static List<String> fixedSplit(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        int step = Math.max(1, size - overlap);
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(text.length(), index + size);
            // 回退到最近的标点或换行，避免截断语义
            if (end < text.length()) {
                int back = end;
                while (back > index + size / 2 && !isBreakChar(text.charAt(back - 1))) {
                    back--;
                }
                end = back;
            }
            String chunk = text.substring(index, end).trim();
            if (StrUtil.isNotBlank(chunk)) {
                chunks.add(chunk);
            }
            index += step;
        }
        return chunks;
    }

    /**
     * 语义分块：先按段落聚合，超长段落按句子切分，再合并不超过 size 的分块
     */
    private static List<String> semanticSplit(String text, int size) {
        List<String> pieces = new ArrayList<>();
        for (String para : text.split(PARA_REGEX)) {
            String trimmed = para.trim();
            if (StrUtil.isBlank(trimmed)) {
                continue;
            }
            if (trimmed.length() <= size) {
                pieces.add(trimmed);
            } else {
                pieces.addAll(sentenceSplit(trimmed, size));
            }
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (current.length() > 0 && current.length() + piece.length() + 1 > size) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (piece.length() > size) {
                // 兜底：极端长句直接硬切
                for (String hard : hardSplit(piece, size)) {
                    chunks.add(hard);
                }
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(piece);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private static List<String> sentenceSplit(String paragraph, int size) {
        List<String> sentences = new ArrayList<>();
        StringBuilder sentence = new StringBuilder();
        for (char c : paragraph.toCharArray()) {
            sentence.append(c);
            if (isBreakChar(c) && sentence.length() >= size / 3) {
                sentences.add(sentence.toString().trim());
                sentence.setLength(0);
            }
        }
        if (sentence.length() > 0) {
            sentences.add(sentence.toString().trim());
        }
        return sentences;
    }

    private static List<String> hardSplit(String text, int size) {
        List<String> result = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(text.length(), index + size);
            result.add(text.substring(index, end).trim());
            index = end;
        }
        return result;
    }

    private static boolean isBreakChar(char c) {
        return "\n。！？；.!?;，,".indexOf(c) >= 0;
    }
}
