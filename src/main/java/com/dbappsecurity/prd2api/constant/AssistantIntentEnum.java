package com.dbappsecurity.prd2api.constant;

import lombok.Getter;

/**
 * 桌面助手意图
 * <p>
 * 由关键词规则命中（不依赖 LLM，保证无模型密钥时也能执行系统操作），
 * 未命中的自由提问统一走 PRODUCT_QA，交给大模型或内置知识库回答。
 */
@Getter
public enum AssistantIntentEnum {

    GREETING("GREETING", "打招呼"),
    TASK_LIST("TASK_LIST", "查看我的任务"),
    TASK_STATUS("TASK_STATUS", "查询任务进度"),
    CANCEL_TASK("CANCEL_TASK", "取消任务"),
    RETRY_TASK("RETRY_TASK", "重试任务"),
    QUOTA("QUOTA", "查看剩余额度"),
    VIP("VIP", "查看会员信息"),
    FILE_LIST("FILE_LIST", "查看已上传文件"),
    ADMIN_STATS("ADMIN_STATS", "查看运营数据"),
    GUIDE_UPLOAD("GUIDE_UPLOAD", "上传指引"),
    GUIDE_EXPORT("GUIDE_EXPORT", "导出指引"),
    PRODUCT_QA("PRODUCT_QA", "平台问答");

    private final String code;
    private final String label;

    AssistantIntentEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
