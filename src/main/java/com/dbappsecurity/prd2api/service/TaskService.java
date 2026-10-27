package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.dto.AgentChatDTO;
import com.dbappsecurity.prd2api.dto.RetryTaskDTO;
import com.dbappsecurity.prd2api.dto.TaskQueryDTO;
import com.dbappsecurity.prd2api.dto.UploadFileDTO;
import com.dbappsecurity.prd2api.vo.ReportExportVO;
import com.dbappsecurity.prd2api.vo.ReviewIssueVO;
import com.dbappsecurity.prd2api.vo.TaskDiffVO;
import com.dbappsecurity.prd2api.vo.TaskVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * 任务服务：创建、查询、重试、追问、导出、清理
 */
public interface TaskService {

    TaskVO createTask(UploadFileDTO dto);

    TaskVO getTask(String taskId);

    /**
     * 校验任务存在且当前调用方有权访问（租户隔离），不返回任务内容
     */
    void checkTaskAccess(String taskId);

    Page<TaskVO> pageTasks(TaskQueryDTO dto);

    void retryTask(RetryTaskDTO dto);

    /**
     * 取消任务：写 Redis 取消标记，流水线在阶段间检查并中断
     */
    void cancelTask(String taskId);

    /**
     * 同一文档两个版本报告的行级对比
     */
    TaskDiffVO diffReport(String baseTaskId, String compareTaskId);

    /**
     * 从评审报告中抽取 ✅确定 / ❓待确认 / ⚠️冲突 条目
     */
    List<ReviewIssueVO> extractIssues(String taskId);

    /**
     * 在已有任务上追加追问，答案追加到报告并返回
     */
    String chat(AgentChatDTO dto);

    ReportExportVO exportReport(String taskId, String format);

    /**
     * 凭一次性 token 读取导出文件
     */
    ExportFile loadExportFile(String token);

    void deleteTask(String taskId);

    /**
     * 清理租户全部任务与向量（管理员）
     */
    int purgeTenant(String tenantId);

    /**
     * 导出文件
     */
    record ExportFile(Resource resource, String fileName, String contentType) {
    }
}
