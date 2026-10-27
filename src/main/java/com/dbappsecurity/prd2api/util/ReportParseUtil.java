package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.vo.ReviewIssueVO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 评审报告解析工具
 * <p>
 * 从阶段4生成的 Markdown 报告里抽取「✅确定 / ❓待确认 / ⚠️冲突」条目，
 * 输出结构化清单，便于前端做评审待办勾选。
 */
public final class ReportParseUtil {

    public static final String TYPE_PENDING = "PENDING";
    public static final String TYPE_CONFLICT = "CONFLICT";
    public static final String TYPE_CONFIRMED = "CONFIRMED";

    private static final String MARK_PENDING = "❓";
    private static final String MARK_CONFLICT = "⚠";
    private static final String MARK_CONFIRMED = "✅";
    private static final int MAX_CONTENT_LENGTH = 1000;

    private ReportParseUtil() {
    }

    public static List<ReviewIssueVO> parseIssues(String markdown) {
        List<ReviewIssueVO> issues = new ArrayList<>();
        if (StrUtil.isBlank(markdown)) {
            return issues;
        }
        Set<String> dedup = new HashSet<>();
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String section = "";
        for (int index = 0; index < lines.length; index++) {
            String raw = lines[index];
            String line = raw.trim();
            if (line.startsWith("#")) {
                section = line.replaceAll("^#+\\s*", "").trim();
                continue;
            }
            if (line.isEmpty()) {
                continue;
            }
            boolean bullet = line.matches("^([-*+]|\\d+[.)])\\s+.*");
            String content = bullet
                    ? line.replaceFirst("^([-*+]|\\d+[.)])\\s+", "").trim()
                    : line;
            if (content.length() < 2) {
                continue;
            }
            String type = resolveType(content);
            boolean inPendingSection = section.contains("待确认");
            if (type == null && !(inPendingSection && bullet)) {
                continue;
            }
            if (type == null) {
                type = TYPE_PENDING;
            }
            String key = section + "|" + type + "|" + content;
            if (!dedup.add(key)) {
                continue;
            }
            ReviewIssueVO vo = new ReviewIssueVO();
            vo.setType(type);
            vo.setLabel(label(type));
            vo.setContent(StrUtil.maxLength(content, MAX_CONTENT_LENGTH));
            vo.setSection(section);
            vo.setLineNo(index + 1);
            vo.setRaw(StrUtil.maxLength(line, MAX_CONTENT_LENGTH));
            issues.add(vo);
        }
        return issues;
    }

    private static String resolveType(String content) {
        if (content.contains(MARK_CONFLICT)) {
            return TYPE_CONFLICT;
        }
        if (content.contains(MARK_PENDING)) {
            return TYPE_PENDING;
        }
        if (content.contains(MARK_CONFIRMED)) {
            return TYPE_CONFIRMED;
        }
        return null;
    }

    private static String label(String type) {
        return switch (type) {
            case TYPE_CONFLICT -> "冲突需求";
            case TYPE_CONFIRMED -> "确定需求";
            default -> "待确认";
        };
    }
}
