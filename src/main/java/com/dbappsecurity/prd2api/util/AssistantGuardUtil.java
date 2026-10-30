package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Agent 输入护栏：提示词注入 / 越权指令识别
 * <p>
 * 只做确定性规则识别，不依赖模型判断：命中后不会直接拒绝用户，而是把本轮所有写操作
 * 升级为「人工确认」，避免被“忽略之前的指令”“直接执行不用确认”一类话术诱导执行危险工具。
 */
public final class AssistantGuardUtil {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(忽略|无视|绕过|忘记).{0,8}(之前|以上|前面|上面|系统|全部).{0,6}(指令|规则|提示|限制)"),
            Pattern.compile("ignore\\s+(all\\s+|the\\s+)?(previous|above|prior)\\s+instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(输出|打印|展示|告诉我|复述).{0,8}(系统提示词|系统提示|你的提示词|system\\s*prompt)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(进入|开启).{0,4}(开发者模式|越狱模式|debug\\s*mode|dan模式)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(不用|无需|跳过|免去).{0,4}(确认|授权|审批)"),
            Pattern.compile("(直接|马上|立刻).{0,4}(执行|取消|删除|重试).{0,6}(不用|无需|不要).{0,4}(确认|问)"),
            Pattern.compile("(你现在是|从现在开始你是).{0,12}(不受限制|没有限制|无限制)"),
            Pattern.compile("(不要|别).{0,6}(告诉|通知).{0,4}(用户|管理员)"));

    private AssistantGuardUtil() {
    }

    /**
     * 命中返回命中的规则说明，未命中返回 null
     */
    public static String detectInjection(String message) {
        if (StrUtil.isBlank(message)) {
            return null;
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(compact).find()) {
                return StrUtil.maxLength(pattern.pattern(), 40);
            }
        }
        return null;
    }
}
