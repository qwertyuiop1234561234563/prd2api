package com.dbappsecurity.prd2api.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkUtilTest {

    private static final int SIZE = 100;

    @Test
    void blankTextShouldReturnEmpty() {
        assertTrue(ChunkUtil.chunk(null, ChunkUtil.STRATEGY_FIXED, SIZE, 10).isEmpty());
        assertTrue(ChunkUtil.chunk("   \n\n ", ChunkUtil.STRATEGY_SEMANTIC, SIZE, 10).isEmpty());
    }

    @Test
    void fixedSplitShouldRespectSizeAndTerminate() {
        String text = "第一段内容。".repeat(200);
        List<String> chunks = ChunkUtil.chunk(text, ChunkUtil.STRATEGY_FIXED, SIZE, 20);
        assertFalse(chunks.isEmpty());
        chunks.forEach(chunk -> assertTrue(chunk.length() <= SIZE, "分块超长: " + chunk.length()));
        // 覆盖全部内容，不能因 step 计算错误而漏切
        int total = chunks.stream().mapToInt(String::length).sum();
        assertTrue(total >= text.length() - (chunks.size() * 20), "分块总长度异常: " + total);
    }

    @Test
    void fixedSplitShouldHandleOverlapNotSmallerThanSize() {
        // overlap >= size 时 step 仍必须为正，避免死循环
        List<String> chunks = ChunkUtil.chunk("abcdef".repeat(50), ChunkUtil.STRATEGY_FIXED, SIZE, 200);
        assertFalse(chunks.isEmpty());
    }

    @Test
    void semanticSplitShouldMergeShortParagraphs() {
        String text = "段落一\n\n段落二\n\n段落三";
        List<String> chunks = ChunkUtil.chunk(text, ChunkUtil.STRATEGY_SEMANTIC, SIZE, 0);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("段落一"));
        assertTrue(chunks.get(0).contains("段落三"));
    }

    @Test
    void semanticSplitShouldSplitLongParagraph() {
        String paragraph = "这是一个很长的句子，".repeat(60);
        List<String> chunks = ChunkUtil.chunk(paragraph, ChunkUtil.STRATEGY_SEMANTIC, SIZE, 0);
        assertTrue(chunks.size() > 1);
        chunks.forEach(chunk -> assertTrue(chunk.length() <= SIZE, "分块超长: " + chunk.length()));
    }
}
