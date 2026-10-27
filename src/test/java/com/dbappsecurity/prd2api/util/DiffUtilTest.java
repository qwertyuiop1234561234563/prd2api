package com.dbappsecurity.prd2api.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffUtilTest {

    @Test
    void identicalTextShouldHaveNoChanges() {
        DiffUtil.DiffResult result = DiffUtil.diff("a\nb\nc", "a\nb\nc");
        assertEquals(0, result.added());
        assertEquals(0, result.removed());
        assertEquals(3, result.unchanged());
        assertFalse(result.truncated());
    }

    @Test
    void shouldDetectAddedAndRemovedLines() {
        DiffUtil.DiffResult result = DiffUtil.diff("a\nb\nc", "a\nx\nc");
        assertEquals(1, result.added());
        assertEquals(1, result.removed());
        assertEquals(2, result.unchanged());
        assertTrue(result.diffText().contains("+x"));
        assertTrue(result.diffText().contains("-b"));
    }

    @Test
    void emptyInputsShouldBeSupported() {
        DiffUtil.DiffResult addedAll = DiffUtil.diff(null, "a\nb");
        assertEquals(2, addedAll.added());
        assertEquals(0, addedAll.removed());

        DiffUtil.DiffResult removedAll = DiffUtil.diff("a\nb", "");
        assertEquals(2, removedAll.removed());
        assertEquals(0, removedAll.added());
    }

    @Test
    void oversizedInputShouldBeTruncated() {
        String text = "line\n".repeat(2000);
        DiffUtil.DiffResult result = DiffUtil.diff(text, text);
        assertTrue(result.truncated());
        assertTrue(result.diffText().split("\n").length <= 1000);
    }
}
