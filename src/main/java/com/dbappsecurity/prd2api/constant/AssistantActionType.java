package com.dbappsecurity.prd2api.constant;

/**
 * 助手回复附带的动作类型
 * <p>
 * 前端按 type 渲染：卡片数据直接展示，NAVIGATE 用于跳转页面；
 * NONE 表示纯文本回复，前端只需渲染文字。
 */
public final class AssistantActionType {

    /** 纯文本回复 */
    public static final String NONE = "NONE";
    /** 任务列表卡片 */
    public static final String TASK_LIST = "TASK_LIST";
    /** 单个任务状态卡片 */
    public static final String TASK_DETAIL = "TASK_DETAIL";
    /** 额度卡片 */
    public static final String QUOTA = "QUOTA";
    /** 会员卡片 */
    public static final String VIP = "VIP";
    /** 文件列表卡片 */
    public static final String FILE_LIST = "FILE_LIST";
    /** 运营数据卡片（仅管理员） */
    public static final String ADMIN_STATS = "ADMIN_STATS";
    /** 页面跳转 */
    public static final String NAVIGATE = "NAVIGATE";
    /** 危险操作待人工确认 */
    public static final String CONFIRM = "CONFIRM";

    private AssistantActionType() {
    }
}
