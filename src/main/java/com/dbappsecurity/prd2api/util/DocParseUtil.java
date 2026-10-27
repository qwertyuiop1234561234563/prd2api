package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文档解析工具：md / txt / docx / pdf，统一输出纯文本
 */
@Slf4j
public final class DocParseUtil {

    private DocParseUtil() {
    }

    public static String parse(byte[] bytes, String suffix) {
        String raw = switch (suffix) {
            case "docx" -> parseDocx(bytes);
            case "pdf" -> parsePdf(bytes);
            default -> new String(bytes, StandardCharsets.UTF_8);
        };
        return clean(raw);
    }

    private static String parseDocx(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder builder = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                if (StrUtil.isNotBlank(paragraph.getText())) {
                    builder.append(paragraph.getText()).append('\n');
                }
            }
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder line = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        line.append(cell.getText()).append(" | ");
                    }
                    builder.append(line).append('\n');
                }
            }
            return builder.toString();
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "docx 解析失败: " + e.getMessage(), e);
        }
    }

    private static String parsePdf(byte[] bytes) {
        try (PDDocument document = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "pdf 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 文本清洗：剔除 HTML 标签、Markdown 标记、控制字符、乱码与多余空白
     */
    public static String clean(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        String text = raw
                // HTML 标签
                .replaceAll("<script[\\s\\S]*?</script>", " ")
                .replaceAll("<style[\\s\\S]*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                // Markdown 代码块围栏、图片、链接
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                // 标题/强调/引用/分隔线等标记
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*>\\s*", "")
                .replaceAll("\\*{2,}|_{2,}", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "- ")
                .replaceAll("(?m)^\\s*-{3,}\\s*$", "")
                // 控制字符与零宽字符
                .replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]", "")
                .replaceAll("[\\u200B-\\u200F\\uFEFF]", "")
                // 空白收敛
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }
}
