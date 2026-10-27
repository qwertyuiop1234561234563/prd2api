package com.dbappsecurity.prd2api.util;

import com.dbappsecurity.prd2api.vo.ReviewIssueVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportParseUtilTest {

    @Test
    void blankReportShouldReturnEmpty() {
        assertTrue(ReportParseUtil.parseIssues(null).isEmpty());
        assertTrue(ReportParseUtil.parseIssues("  ").isEmpty());
    }

    @Test
    void shouldExtractMarkedIssues() {
        String markdown = """
                # 需求评审
                - ❓ 用户是否需要支持批量导入？
                - ⚠ 订单状态与支付状态存在冲突
                - ✅ 登录流程已确定
                - 普通说明不需要提取
                """;
        List<ReviewIssueVO> issues = ReportParseUtil.parseIssues(markdown);
        assertEquals(3, issues.size());
        assertEquals(ReportParseUtil.TYPE_PENDING, issues.get(0).getType());
        assertEquals("待确认", issues.get(0).getLabel());
        assertEquals(ReportParseUtil.TYPE_CONFLICT, issues.get(1).getType());
        assertEquals(ReportParseUtil.TYPE_CONFIRMED, issues.get(2).getType());
        assertEquals("需求评审", issues.get(0).getSection());
        assertEquals(2, issues.get(0).getLineNo());
    }

    @Test
    void bulletsInPendingSectionShouldBePending() {
        String markdown = """
                ## 待确认事项
                - 是否需要国际化支持
                * 数据保留期限待定
                """;
        List<ReviewIssueVO> issues = ReportParseUtil.parseIssues(markdown);
        assertEquals(2, issues.size());
        issues.forEach(issue -> assertEquals(ReportParseUtil.TYPE_PENDING, issue.getType()));
    }

    @Test
    void shouldDeduplicateSameEntry() {
        String markdown = """
                # 待确认
                - ❓ 同一个问题
                - ❓ 同一个问题
                """;
        assertEquals(1, ReportParseUtil.parseIssues(markdown).size());
    }
}
