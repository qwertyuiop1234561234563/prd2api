package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;

import java.util.HashSet;
import java.util.Set;

/**
 * 相似度工具：文本 bigram 相似度（RAG 降级召回、文档查重）
 */
public final class SimilarityUtil {

    private SimilarityUtil() {
    }

    /**
     * 字符 bigram Dice 系数，0~1
     */
    public static double textSimilarity(String a, String b) {
        if (StrUtil.isBlank(a) || StrUtil.isBlank(b)) {
            return 0.0D;
        }
        Set<String> bigramsA = bigrams(a);
        Set<String> bigramsB = bigrams(b);
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) {
            return 0.0D;
        }
        int overlap = 0;
        for (String gram : bigramsA) {
            if (bigramsB.contains(gram)) {
                overlap++;
            }
        }
        return (2.0D * overlap) / (bigramsA.size() + bigramsB.size());
    }

    /**
     * 文档级查重：抽样分块后取平均相似度
     */
    public static double documentSimilarity(String textA, String textB) {
        if (StrUtil.isBlank(textA) || StrUtil.isBlank(textB)) {
            return 0.0D;
        }
        String sampleA = textA.length() > 3000 ? textA.substring(0, 3000) : textA;
        String sampleB = textB.length() > 3000 ? textB.substring(0, 3000) : textB;
        return textSimilarity(sampleA, sampleB);
    }

    private static Set<String> bigrams(String text) {
        String normalized = text.replaceAll("\\s+", "");
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }
}
