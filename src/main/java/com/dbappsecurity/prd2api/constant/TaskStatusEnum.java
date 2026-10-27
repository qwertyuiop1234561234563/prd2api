package com.dbappsecurity.prd2api.constant;

import lombok.Getter;

import java.util.Objects;

/**
 * 任务状态枚举
 */
@Getter
public enum TaskStatusEnum {

    INIT("INIT", "已创建"),
    PROCESS("PROCESS", "解析中"),
    SUCCESS("SUCCESS", "解析成功"),
    FAIL("FAIL", "解析失败");

    private final String code;
    private final String desc;

    TaskStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static boolean isValid(String code) {
        for (TaskStatusEnum value : values()) {
            if (Objects.equals(value.code, code)) {
                return true;
            }
        }
        return false;
    }
}
