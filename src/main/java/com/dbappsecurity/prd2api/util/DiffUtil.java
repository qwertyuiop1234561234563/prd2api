package com.dbappsecurity.prd2api.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本行级差异工具
 * <p>
 * 基于 LCS 生成 unified diff 风格的逐行差异，用于同一文档不同版本评审报告的对比。
 * 为避免长报告撑爆内存，单侧行数超过 {@link #MAX_INPUT_LINES} 时截断并标记 truncated。
 */
public final class DiffUtil {

    private static final int MAX_INPUT_LINES = 1500;
    private static final int MAX_OUTPUT_LINES = 1000;

    private DiffUtil() {
    }

    public record DiffResult(String diffText, int added, int removed, int unchanged, boolean truncated) {
    }

    public static DiffResult diff(String baseText, String compareText) {
        List<String> base = splitLines(baseText);
        List<String> compare = splitLines(compareText);

        boolean truncated = false;
        if (base.size() > MAX_INPUT_LINES) {
            base = base.subList(0, MAX_INPUT_LINES);
            truncated = true;
        }
        if (compare.size() > MAX_INPUT_LINES) {
            compare = compare.subList(0, MAX_INPUT_LINES);
            truncated = true;
        }

        int n = base.size();
        int m = compare.size();
        // lcs[i][j] 表示 base[i..] 与 compare[j..] 的最长公共子序列长度
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = base.get(i).equals(compare.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        List<String> lines = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int unchanged = 0;
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (base.get(i).equals(compare.get(j))) {
                lines.add(" " + base.get(i));
                unchanged++;
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                lines.add("-" + base.get(i));
                removed++;
                i++;
            } else {
                lines.add("+" + compare.get(j));
                added++;
                j++;
            }
        }
        while (i < n) {
            lines.add("-" + base.get(i));
            removed++;
            i++;
        }
        while (j < m) {
            lines.add("+" + compare.get(j));
            added++;
            j++;
        }

        if (lines.size() > MAX_OUTPUT_LINES) {
            lines = lines.subList(0, MAX_OUTPUT_LINES);
            truncated = true;
        }
        return new DiffResult(String.join("\n", lines), added, removed, unchanged, truncated);
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0;
        for (int index = 0; index < normalized.length(); index++) {
            if (normalized.charAt(index) == '\n') {
                lines.add(normalized.substring(start, index));
                start = index + 1;
            }
        }
        if (start < normalized.length()) {
            lines.add(normalized.substring(start));
        }
        return lines;
    }
}
