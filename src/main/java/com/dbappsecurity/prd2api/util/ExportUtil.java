package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 报告导出工具：Markdown -> HTML / DOCX
 */
@Slf4j
public final class ExportUtil {

    private ExportUtil() {
    }

    /**
     * Markdown 转 HTML（自包含样式，可直接浏览器打开）
     */
    public static String markdownToHtml(String markdown, String title) {
        String body = renderBody(markdown);
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s</title>
                <style>
                  body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
                       max-width:980px;margin:0 auto;padding:32px 24px;color:#1f2328;line-height:1.75;background:#fff}
                  h1{font-size:26px;border-bottom:2px solid #eaecef;padding-bottom:8px}
                  h2{font-size:21px;border-bottom:1px solid #eaecef;padding-bottom:6px;margin-top:28px}
                  h3{font-size:17px;margin-top:20px}
                  code{background:#f6f8fa;padding:2px 5px;border-radius:4px;font-size:90%%}
                  pre{background:#f6f8fa;padding:14px;border-radius:6px;overflow:auto}
                  pre code{background:none;padding:0}
                  table{border-collapse:collapse;width:100%%;margin:14px 0;font-size:14px}
                  th,td{border:1px solid #d0d7de;padding:8px 10px;text-align:left}
                  th{background:#f6f8fa}
                  blockquote{border-left:4px solid #d0d7de;margin:0;padding:4px 14px;color:#57606a;background:#fafbfc}
                  ul,ol{padding-left:24px}
                  .footer{margin-top:48px;color:#8b949e;font-size:12px;text-align:center}
                </style>
                </head>
                <body>
                %s
                <div class="footer">由 Prd2Api 自动生成，结论需人工复核</div>
                </body>
                </html>
                """.formatted(escape(title), body);
    }

    private static String renderBody(String markdown) {
        StringBuilder body = new StringBuilder();
        StringBuilder codeBuffer = null;
        List<String> tableBuffer = new ArrayList<>();
        boolean inList = false;

        String[] lines = StrUtil.nullToEmpty(markdown).split("\n", -1);
        for (String raw : lines) {
            String line = raw.replace("\r", "");
            String trimmed = line.trim();

            if (codeBuffer != null) {
                if (trimmed.startsWith("```")) {
                    body.append("<pre><code>").append(escape(codeBuffer.toString())).append("</code></pre>\n");
                    codeBuffer = null;
                } else {
                    codeBuffer.append(line).append('\n');
                }
                continue;
            }
            if (trimmed.startsWith("```")) {
                closeTable(body, tableBuffer);
                closeList(body, inList);
                inList = false;
                codeBuffer = new StringBuilder();
                continue;
            }
            if (isTableRow(trimmed)) {
                closeList(body, inList);
                inList = false;
                tableBuffer.add(trimmed);
                continue;
            }
            closeTable(body, tableBuffer);

            if (StrUtil.isBlank(trimmed)) {
                closeList(body, inList);
                inList = false;
                continue;
            }
            if (trimmed.matches("^#{1,6}\\s+.*")) {
                closeList(body, inList);
                inList = false;
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                    level++;
                }
                body.append("<h").append(level).append(">")
                        .append(inline(trimmed.substring(level).trim()))
                        .append("</h").append(level).append(">\n");
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                if (!inList) {
                    body.append("<ul>\n");
                    inList = true;
                }
                body.append("<li>").append(inline(trimmed.substring(2).trim())).append("</li>\n");
                continue;
            }
            if (trimmed.matches("^\\d+\\.\\s+.*")) {
                closeList(body, inList);
                inList = false;
                body.append("<ol><li>").append(inline(trimmed.replaceFirst("^\\d+\\.\\s+", "")))
                        .append("</li></ol>\n");
                continue;
            }
            if (trimmed.startsWith("> ")) {
                closeList(body, inList);
                inList = false;
                body.append("<blockquote>").append(inline(trimmed.substring(2))).append("</blockquote>\n");
                continue;
            }
            closeList(body, inList);
            inList = false;
            body.append("<p>").append(inline(trimmed)).append("</p>\n");
        }

        if (codeBuffer != null) {
            body.append("<pre><code>").append(escape(codeBuffer.toString())).append("</code></pre>\n");
        }
        closeTable(body, tableBuffer);
        closeList(body, inList);
        return body.toString();
    }

    private static void closeList(StringBuilder body, boolean inList) {
        if (inList) {
            body.append("</ul>\n");
        }
    }

    private static boolean isTableRow(String trimmed) {
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2;
    }

    private static void closeTable(StringBuilder body, List<String> tableBuffer) {
        if (tableBuffer.isEmpty()) {
            return;
        }
        body.append("<table>\n");
        boolean headerDone = false;
        for (String row : tableBuffer) {
            if (row.matches("^\\|?[\\s:|-]+\\|?$")) {
                continue;
            }
            List<String> cells = splitRow(row);
            String tag = headerDone ? "td" : "th";
            body.append("<tr>");
            for (String cell : cells) {
                body.append('<').append(tag).append('>').append(inline(cell)).append("</").append(tag).append('>');
            }
            body.append("</tr>\n");
            headerDone = true;
        }
        body.append("</table>\n");
        tableBuffer.clear();
    }

    private static List<String> splitRow(String row) {
        String content = row.trim();
        if (content.startsWith("|")) {
            content = content.substring(1);
        }
        if (content.endsWith("|")) {
            content = content.substring(0, content.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : content.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    /**
     * Markdown 转 DOCX，写入目标文件
     */
    public static void markdownToDocx(String markdown, Path target) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (XWPFDocument document = new XWPFDocument(); OutputStream out = Files.newOutputStream(target)) {
                List<String> tableBuffer = new ArrayList<>();
                boolean inCode = false;
                for (String raw : StrUtil.nullToEmpty(markdown).split("\n", -1)) {
                    String line = raw.replace("\r", "");
                    String trimmed = line.trim();
                    if (trimmed.startsWith("```")) {
                        writeTable(document, tableBuffer);
                        inCode = !inCode;
                        continue;
                    }
                    if (inCode) {
                        XWPFParagraph paragraph = document.createParagraph();
                        XWPFRun run = paragraph.createRun();
                        run.setFontFamily("Consolas");
                        run.setFontSize(9);
                        run.setText(line);
                        continue;
                    }
                    if (isTableRow(trimmed)) {
                        tableBuffer.add(trimmed);
                        continue;
                    }
                    writeTable(document, tableBuffer);
                    if (StrUtil.isBlank(trimmed)) {
                        document.createParagraph();
                        continue;
                    }
                    if (trimmed.matches("^#{1,6}\\s+.*")) {
                        int level = 0;
                        while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                            level++;
                        }
                        addTextParagraph(document, trimmed.substring(level).trim(), headingSize(level), true);
                        continue;
                    }
                    if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                        addTextParagraph(document, "• " + trimmed.substring(2).trim(), 11, false);
                        continue;
                    }
                    addTextParagraph(document, trimmed, 11, false);
                }
                writeTable(document, tableBuffer);
                document.write(out);
            }
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "DOCX 导出失败: " + e.getMessage(), e);
        }
    }

    private static int headingSize(int level) {
        return switch (level) {
            case 1 -> 20;
            case 2 -> 16;
            case 3 -> 14;
            default -> 12;
        };
    }

    private static void addTextParagraph(XWPFDocument document, String text, int fontSize, boolean bold) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setBold(bold);
        run.setFontSize(fontSize);
        run.setText(text);
    }

    private static void writeTable(XWPFDocument document, List<String> tableBuffer) {
        if (tableBuffer.isEmpty()) {
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (String row : tableBuffer) {
            if (row.matches("^\\|?[\\s:|-]+\\|?$")) {
                continue;
            }
            rows.add(splitRow(row));
        }
        tableBuffer.clear();
        if (rows.isEmpty()) {
            return;
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        XWPFTable table = document.createTable(rows.size(), columns);
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            for (int j = 0; j < columns; j++) {
                String value = j < row.size() ? row.get(j) : "";
                table.getRow(i).getCell(j).setText(value);
            }
        }
        document.createParagraph();
    }

    private static String inline(String text) {
        String escaped = escape(text);
        escaped = escaped.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        escaped = escaped.replaceAll("`([^`]+)`", "<code>$1</code>");
        escaped = escaped.replaceAll("~~([^~]+)~~", "<del>$1</del>");
        return escaped;
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * 生成导出文件路径
     */
    public static Path exportPath(String dir, String fileName) {
        try {
            Path directory = Paths.get(dir);
            Files.createDirectories(directory);
            return directory.resolve(fileName);
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "导出目录创建失败: " + e.getMessage(), e);
        }
    }
}
