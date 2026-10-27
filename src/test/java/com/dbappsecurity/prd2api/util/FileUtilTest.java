package com.dbappsecurity.prd2api.util;

import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void sanitizeShouldStripPathAndControlChars() {
        assertEquals("passwd", FileUtil.sanitizeFileName("../../etc/passwd"));
        assertEquals("ab.md", FileUtil.sanitizeFileName("C:\\upload\\a\u0000b.md"));
        assertEquals("unknown", FileUtil.sanitizeFileName("   "));
        assertEquals("unknown", FileUtil.sanitizeFileName(null));
    }

    @Test
    void sanitizeShouldLimitLengthAndKeepSuffix() {
        String longName = "x".repeat(300) + ".docx";
        String sanitized = FileUtil.sanitizeFileName(longName);
        assertTrue(sanitized.length() <= 105, "清洗后长度应受控: " + sanitized.length());
        assertTrue(sanitized.endsWith(".docx"));
    }

    @Test
    void suffixShouldRejectUnsupportedOrMissing() {
        BizException missing = assertThrows(BizException.class,
                () -> FileUtil.checkSuffix("readme"));
        assertEquals(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED.getCode(), missing.getCode());

        BizException exe = assertThrows(BizException.class,
                () -> FileUtil.checkSuffix("evil.exe"));
        assertEquals(BizErrorCodeEnum.FILE_TYPE_NOT_ALLOWED.getCode(), exe.getCode());

        assertEquals("docx", FileUtil.checkSuffix("需求.PRD.DOCX"));
    }

    @Test
    void magicCheckShouldRejectDisguisedFiles() {
        assertThrows(BizException.class,
                () -> FileUtil.magicCheck("not-pdf".getBytes(StandardCharsets.UTF_8), "pdf"));
        assertThrows(BizException.class,
                () -> FileUtil.magicCheck("plain".getBytes(StandardCharsets.UTF_8), "docx"));
        // 文本中出现 NUL 字节视为二进制伪装
        assertThrows(BizException.class,
                () -> FileUtil.magicCheck(new byte[]{'a', 'b', 0, 'd'}, "txt"));

        FileUtil.magicCheck("%PDF-1.7".getBytes(StandardCharsets.UTF_8), "pdf");
        FileUtil.magicCheck(new byte[]{'P', 'K', 3, 4}, "docx");
        FileUtil.magicCheck("正常文本".getBytes(StandardCharsets.UTF_8), "md");
    }

    @Test
    void saveLocalShouldNeverEscapeBaseDirectory() throws Exception {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        String path = FileUtil.saveLocal(tempDir.toString(), "../evil", content, "txt");

        Path base = tempDir.toAbsolutePath().normalize();
        Path saved = Paths.get(path).toAbsolutePath().normalize();
        assertTrue(saved.startsWith(base), "落盘路径必须位于基础目录内: " + saved);
        assertArrayEquals(content, Files.readAllBytes(saved));
        assertFalse(saved.toString().contains(".."));
    }
}
