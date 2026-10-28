package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.constant.AssistantIntentEnum;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 助手意图识别
 * <p>
 * 纯规则匹配，不消耗 token、不依赖大模型：命中系统操作类意图直接执行查询/操作，
 * 只有自由问答才走大模型。规则按“越具体越靠前”排序，避免“怎么上传”被文件列表误命中。
 */
@Component
public class AssistantIntentResolver {

    /** 任务ID 为 32 位十六进制 */
    private static final Pattern TASK_ID = Pattern.compile("[0-9a-fA-F]{32}");

    private static final List<String> CANCEL = List.of("取消任务", "中止任务", "停止任务", "终止任务");
    private static final List<String> RETRY = List.of("重试", "重新解析", "重新跑", "再解析一次");
    private static final List<String> STATUS = List.of("进度", "状态", "到哪", "卡住", "怎么样了", "好了吗", "完成了吗");
    private static final List<String> STATS = List.of("统计", "大盘", "运营数据", "成功率", "总数");
    private static final List<String> GUIDE_UPLOAD = List.of("怎么上传", "如何上传", "上传格式", "支持什么格式",
            "上传不了", "上传失败");
    private static final List<String> GUIDE_EXPORT = List.of("怎么导出", "如何导出", "导出报告", "下载报告", "导出");
    private static final List<String> VIP = List.of("vip", "会员", "套餐", "价格", "多少钱", "续费", "到期", "权益");
    private static final List<String> QUOTA = List.of("额度", "配额", "剩余次数", "还能上传", "用量", "限流");
    private static final List<String> FILE_LIST = List.of("文件列表", "我的文件", "上传的文件", "上传过",
            "文档列表", "我的文档", "哪些文件", "文件有哪些", "上传记录", "我的上传", "传了哪些");
    private static final List<String> TASK_LIST = List.of("我的任务", "任务列表", "解析记录", "历史任务",
            "最近任务", "我的报告", "任务记录");
    private static final List<String> GREETING = List.of("你好", "您好", "hi", "hello", "在吗", "你是谁",
            "早上好", "晚上好");

    public AssistantIntentEnum resolve(String rawMessage) {
        String message = StrUtil.blankToDefault(rawMessage, "").toLowerCase().trim();
        if (message.isEmpty()) {
            return AssistantIntentEnum.PRODUCT_QA;
        }
        boolean hasTaskId = TASK_ID.matcher(message).find();
        if (containsAny(message, CANCEL)) {
            return AssistantIntentEnum.CANCEL_TASK;
        }
        if (containsAny(message, RETRY) && (message.contains("任务") || hasTaskId)) {
            return AssistantIntentEnum.RETRY_TASK;
        }
        if (containsAny(message, STATUS) || (hasTaskId && message.contains("任务"))) {
            return AssistantIntentEnum.TASK_STATUS;
        }
        if (containsAny(message, STATS) && (message.contains("查") || message.contains("看")
                || message.contains("多少") || message.contains("数据"))) {
            return AssistantIntentEnum.ADMIN_STATS;
        }
        if (containsAny(message, GUIDE_UPLOAD)) {
            return AssistantIntentEnum.GUIDE_UPLOAD;
        }
        if (containsAny(message, GUIDE_EXPORT)) {
            return AssistantIntentEnum.GUIDE_EXPORT;
        }
        if (containsAny(message, VIP)) {
            return AssistantIntentEnum.VIP;
        }
        if (containsAny(message, QUOTA)) {
            return AssistantIntentEnum.QUOTA;
        }
        if (containsAny(message, FILE_LIST)) {
            return AssistantIntentEnum.FILE_LIST;
        }
        if (containsAny(message, TASK_LIST)) {
            return AssistantIntentEnum.TASK_LIST;
        }
        if (containsAny(message, GREETING)) {
            return AssistantIntentEnum.GREETING;
        }
        return AssistantIntentEnum.PRODUCT_QA;
    }

    /**
     * 提取消息中的任务ID，未找到返回 null
     */
    public String extractTaskId(String rawMessage) {
        if (StrUtil.isBlank(rawMessage)) {
            return null;
        }
        var matcher = TASK_ID.matcher(rawMessage);
        return matcher.find() ? matcher.group() : null;
    }

    private boolean containsAny(String message, List<String> keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
