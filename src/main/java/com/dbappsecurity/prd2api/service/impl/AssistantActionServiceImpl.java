package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.AssistantActionType;
import com.dbappsecurity.prd2api.constant.AssistantIntentEnum;
import com.dbappsecurity.prd2api.constant.TaskStatusEnum;
import com.dbappsecurity.prd2api.dto.FileQueryDTO;
import com.dbappsecurity.prd2api.dto.RetryTaskDTO;
import com.dbappsecurity.prd2api.dto.TaskQueryDTO;
import com.dbappsecurity.prd2api.service.AssistantActionService;
import com.dbappsecurity.prd2api.service.FileService;
import com.dbappsecurity.prd2api.service.QuotaResolver;
import com.dbappsecurity.prd2api.service.StatsService;
import com.dbappsecurity.prd2api.service.TaskService;
import com.dbappsecurity.prd2api.service.VipService;
import com.dbappsecurity.prd2api.util.AssistantIntentResolver;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.AdminStatsVO;
import com.dbappsecurity.prd2api.vo.FileMetaVO;
import com.dbappsecurity.prd2api.vo.MyVipVO;
import com.dbappsecurity.prd2api.vo.QuotaInfoVO;
import com.dbappsecurity.prd2api.vo.TaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面助手动作执行实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantActionServiceImpl implements AssistantActionService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final int PREVIEW_SIZE = 5;

    private final TaskService taskService;
    private final FileService fileService;
    private final VipService vipService;
    private final QuotaResolver quotaResolver;
    private final StatsService statsService;
    private final AssistantIntentResolver intentResolver;

    @Override
    public boolean executable(AssistantIntentEnum intent) {
        return switch (intent) {
            case TASK_LIST, TASK_STATUS, CANCEL_TASK, RETRY_TASK, QUOTA, VIP,
                    FILE_LIST, ADMIN_STATS, GUIDE_UPLOAD, GUIDE_EXPORT -> true;
            default -> false;
        };
    }

    @Override
    public ActionResult execute(AssistantIntentEnum intent, String message) {
        return switch (intent) {
            case TASK_LIST -> taskList();
            case TASK_STATUS -> taskStatus(message);
            case CANCEL_TASK -> cancelTask(message);
            case RETRY_TASK -> retryTask(message);
            case QUOTA -> quota();
            case VIP -> vip();
            case FILE_LIST -> fileList();
            case ADMIN_STATS -> adminStats();
            case GUIDE_UPLOAD -> guide("上传入口在「文件上传」页：支持 md、txt、docx、pdf，"
                    + "上传成功后点“开始解析”即创建任务，解析进度可在任务列表查看。",
                    AssistantActionType.NAVIGATE, "去上传", "/upload");
            case GUIDE_EXPORT -> guide("报告生成后，在任务详情右上角选择 Markdown / HTML / DOCX 导出，"
                    + "下载链接默认 5 分钟内有效，过期重新导出即可。",
                    AssistantActionType.NAVIGATE, "去任务列表", "/tasks");
            default -> null;
        };
    }

    private ActionResult taskList() {
        TaskQueryDTO query = new TaskQueryDTO();
        query.setPageNum(1);
        query.setPageSize(PREVIEW_SIZE);
        Page<TaskVO> page = taskService.pageTasks(query);
        List<TaskVO> records = page.getRecords();
        if (records.isEmpty()) {
            return new ActionResult("你还没有解析任务。上传 PRD 后点“开始解析”，我就能帮你查进度啦。",
                    AssistantActionType.TASK_LIST, "去上传", "/upload",
                    Map.of("total", 0, "records", List.of()));
        }
        StringBuilder reply = new StringBuilder("你最近有 ").append(page.getTotal())
                .append(" 个任务，最新的 ").append(records.size()).append(" 个：\n");
        List<Map<String, Object>> data = new ArrayList<>();
        int index = 1;
        for (TaskVO task : records) {
            reply.append(index++).append(". ").append(shortId(task.getTaskId()))
                    .append("：").append(statusLabel(task.getTaskStatus()))
                    .append("（").append(task.getProgress() == null ? 0 : task.getProgress()).append("%）")
                    .append("，").append(formatTime(task.getCreateTime())).append('\n');
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", task.getTaskId());
            item.put("taskStatus", task.getTaskStatus());
            item.put("statusLabel", statusLabel(task.getTaskStatus()));
            item.put("progress", task.getProgress());
            item.put("fileMetaId", task.getFileMetaId());
            item.put("createTime", task.getCreateTime());
            data.add(item);
        }
        reply.append("需要看某个任务的详情，把任务 ID 发给我就行。");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", page.getTotal());
        payload.put("records", data);
        return new ActionResult(reply.toString(), AssistantActionType.TASK_LIST,
                "查看全部任务", "/tasks", payload);
    }

    private ActionResult taskStatus(String message) {
        String taskId = intentResolver.extractTaskId(message);
        if (StrUtil.isBlank(taskId)) {
            // 没给任务 ID 时直接列最近的，避免只回一句“把 ID 发我”而帮不上忙
            ActionResult recent = taskList();
            return new ActionResult("还不知道你问的是哪个任务，先说下最近这几个：\n" + recent.reply(),
                    recent.actionType(), recent.actionLabel(), recent.actionTarget(), recent.data());
        }
        TaskVO task;
        try {
            task = taskService.getTask(taskId);
        } catch (BizException e) {
            return new ActionResult("没找到这个任务，可能 ID 不对或不属于你所在的租户。",
                    AssistantActionType.NAVIGATE, "去任务列表", "/tasks", null);
        }
        String label = statusLabel(task.getTaskStatus());
        int progress = task.getProgress() == null ? 0 : task.getProgress();
        StringBuilder reply = new StringBuilder("任务 ").append(shortId(taskId))
                .append(" 当前状态：").append(label).append("（").append(progress).append("%）");
        if (TaskStatusEnum.FAIL.getCode().equals(task.getTaskStatus())
                && StrUtil.isNotBlank(task.getErrorMsg())) {
            reply.append("，失败原因：").append(StrUtil.maxLength(task.getErrorMsg(), 120));
        } else if (TaskStatusEnum.SUCCESS.getCode().equals(task.getTaskStatus())) {
            reply.append("，报告已生成，可以直接查看或导出。");
        } else if (TaskStatusEnum.PROCESS.getCode().equals(task.getTaskStatus())) {
            reply.append("，正在解析中，稍等一会儿再问我。");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("taskStatus", task.getTaskStatus());
        payload.put("statusLabel", label);
        payload.put("progress", progress);
        return new ActionResult(reply.toString(), AssistantActionType.TASK_DETAIL,
                "打开任务详情", "/tasks/" + taskId, payload);
    }

    private ActionResult cancelTask(String message) {
        String taskId = intentResolver.extractTaskId(message);
        if (StrUtil.isBlank(taskId)) {
            return new ActionResult("取消任务需要提供任务 ID，把 ID 发给我即可。",
                    AssistantActionType.NAVIGATE, "去任务列表", "/tasks", null);
        }
        try {
            taskService.cancelTask(taskId);
        } catch (BizException e) {
            return new ActionResult("取消失败：" + e.getMessage(),
                    AssistantActionType.NAVIGATE, "去任务列表", "/tasks", null);
        }
        return new ActionResult("已提交取消，任务 " + shortId(taskId) + " 会在当前阶段结束后停止。",
                AssistantActionType.NAVIGATE, "去任务列表", "/tasks", null);
    }

    private ActionResult retryTask(String message) {
        String taskId = intentResolver.extractTaskId(message);
        if (StrUtil.isBlank(taskId)) {
            return new ActionResult("重试需要提供任务 ID，把 ID 发给我即可。",
                    AssistantActionType.NAVIGATE, "去任务列表", "/tasks", null);
        }
        try {
            RetryTaskDTO dto = new RetryTaskDTO();
            dto.setTaskId(taskId);
            taskService.retryTask(dto);
        } catch (BizException e) {
            return new ActionResult("重试失败：" + e.getMessage(),
                    AssistantActionType.NAVIGATE, "去任务列表", "/tasks", null);
        }
        return new ActionResult("已重新提交任务 " + shortId(taskId) + "，稍后可在任务列表查看新进度。",
                AssistantActionType.NAVIGATE, "去任务列表", "/tasks", null);
    }

    private ActionResult quota() {
        QuotaInfoVO info = quotaResolver.currentQuota();
        String source = QuotaInfoVO.SOURCE_VIP.equals(info.getSource()) ? "VIP 套餐" : "租户默认配额";
        StringBuilder reply = new StringBuilder();
        reply.append("你当前的额度来源：").append(source);
        if (StrUtil.isNotBlank(info.getPlanName())) {
            reply.append("（").append(info.getPlanName()).append("）");
        }
        reply.append("。今日已上传 ").append(nullToZero(info.getUsedUploadCount()))
                .append("/").append(nullToZero(info.getDailyUploadLimit())).append(" 次");
        reply.append("，token 已用 ").append(nullToZero(info.getUsedTokenCount()))
                .append("/").append(nullToZero(info.getDailyTokenLimit()));
        reply.append("；并发上限 ").append(nullToZero(info.getConcurrentTaskLimit())).append(" 个");
        reply.append("，单文件不超过 ").append(nullToZero(info.getMaxFileSizeMb())).append("MB。");
        return new ActionResult(reply.toString(), AssistantActionType.QUOTA,
                "查看会员中心", "/vip", info);
    }

    private ActionResult vip() {
        MyVipVO vip = vipService.myVip();
        String reply;
        if (vip == null || !Boolean.TRUE.equals(vip.getActive())) {
            reply = "你目前未开通 VIP，使用租户默认额度。开通 VIP 可提升每日上传次数、token 上限、"
                    + "并发任务数和单文件大小上限，也可以提交兑换码直接开通。";
        } else {
            reply = "你当前套餐：" + StrUtil.blankToDefault(vip.getPlanName(), vip.getPlanCode())
                    + "，剩余 " + nullToZero(vip.getRemainDays()) + " 天"
                    + (vip.getExpireTime() == null ? "" : "（到期 " + vip.getExpireTime().toLocalDate() + "）")
                    + "。每日上传 " + nullToZero(vip.getDailyUploadLimit()) + " 次，token 上限 "
                    + nullToZero(vip.getDailyTokenLimit()) + "，并发 " + nullToZero(vip.getConcurrentTaskLimit())
                    + " 个，单文件 ≤ " + nullToZero(vip.getMaxFileSizeMb()) + "MB。";
        }
        return new ActionResult(reply, AssistantActionType.VIP, "前往会员中心", "/vip", vip);
    }

    private ActionResult fileList() {
        FileQueryDTO query = new FileQueryDTO();
        query.setPageNum(1);
        query.setPageSize(PREVIEW_SIZE);
        Page<FileMetaVO> page = fileService.pageFiles(query);
        List<FileMetaVO> records = page.getRecords();
        if (records.isEmpty()) {
            return new ActionResult("你还没有上传过文件。支持 md、txt、docx、pdf，要不要去上传一个？",
                    AssistantActionType.FILE_LIST, "去上传", "/upload",
                    Map.of("total", 0, "records", List.of()));
        }
        StringBuilder reply = new StringBuilder("你最近上传了 ").append(page.getTotal())
                .append(" 个文件：\n");
        int index = 1;
        for (FileMetaVO file : records) {
            reply.append(index++).append(". ").append(file.getFileName())
                    .append("（").append(humanSize(file.getFileSize())).append("，")
                    .append(formatTime(file.getCreateTime())).append("）\n");
        }
        reply.append("要解析哪个文件，去任务列表点“开始解析”即可。");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", page.getTotal());
        payload.put("records", records);
        return new ActionResult(reply.toString(), AssistantActionType.FILE_LIST,
                "查看文件列表", "/files", payload);
    }

    private ActionResult adminStats() {
        if (!TenantContextUtil.isAdmin()) {
            return guide("运营数据只有管理员可以查看。如果你想看自己的任务和额度，直接问我“我的任务”或“我的额度”。",
                    AssistantActionType.NONE, null, null);
        }
        AdminStatsVO stats = statsService.overview(null, 7);
        StringBuilder reply = new StringBuilder("近 7 天全平台总览：");
        if (stats.getOverview() != null) {
            reply.append("任务共 ").append(nullToZero(stats.getOverview().getTotal())).append(" 个，成功 ")
                    .append(nullToZero(stats.getOverview().getSuccess())).append(" 个，失败 ")
                    .append(nullToZero(stats.getOverview().getFail())).append(" 个，今日新增 ")
                    .append(nullToZero(stats.getOverview().getTodayTotal())).append(" 个，成功率 ")
                    .append(nullToZero(stats.getOverview().getSuccessRate())).append("%。");
        }
        return new ActionResult(reply.toString(), AssistantActionType.ADMIN_STATS,
                "打开管理后台", "/admin", stats);
    }

    private ActionResult guide(String reply, String actionType, String label, String target) {
        return new ActionResult(reply, actionType, label, target, null);
    }

    private String statusLabel(String status) {
        for (TaskStatusEnum value : TaskStatusEnum.values()) {
            if (value.getCode().equalsIgnoreCase(status)) {
                return value.getDesc();
            }
        }
        return StrUtil.blankToDefault(status, "未知");
    }

    private String shortId(String taskId) {
        return StrUtil.isBlank(taskId) ? "-" : StrUtil.subPre(taskId, 8);
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? "-" : TIME_FMT.format(time);
    }

    private String humanSize(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return "0B";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.0fKB", kb);
        }
        return String.format("%.1fMB", kb / 1024.0);
    }

    private long nullToZero(Number value) {
        return value == null ? 0L : value.longValue();
    }
}
