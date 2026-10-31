package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII 脱敏工具：手机号、身份证、银行卡、邮箱
 * <p>
 * Agent 的输出会经过模型归纳、工具结果拼装、轨迹落库多条链路，
 * 任何一条链路把原始个人信息带出去都算数据泄露；这里用正则做确定性脱敏，
 * 不依赖模型自觉，保证“模型看不到、用户拿到的是打码后的结果”。
 */
public final class PiiMaskUtil {

    /** 邮箱：保留前两位与域名 */
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?![A-Za-z0-9])");
    /** 18 位身份证：首 4 位 + 掩码 + 末 4 位 */
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<![0-9])[1-9][0-9]{5}[0-9]{8}[0-9Xx]{2,4}(?![0-9])");
    /** 银行卡 16~19 位 */
    private static final Pattern BANK_CARD = Pattern.compile(
            "(?<![0-9])[0-9]{16,19}(?![0-9])");
    /** 手机号 11 位 */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![0-9])1[3-9][0-9]{9}(?![0-9])");

    private PiiMaskUtil() {
    }

    /**
     * 脱敏文本，返回打码后的内容
     */
    public static String mask(String text) {
        return maskAndCount(text).text();
    }

    /**
     * 脱敏文本并统计命中类型，便于埋点
     */
    public static MaskedText maskAndCount(String text) {
        if (StrUtil.isBlank(text)) {
            return new MaskedText(text, Map.of());
        }
        Map<String, Integer> hits = new LinkedHashMap<>();
        String result = text;
        result = replace(result, EMAIL, hits, "email", Matcher::group);
        result = replace(result, ID_CARD, hits, "idCard", Matcher::group);
        result = replace(result, BANK_CARD, hits, "bankCard", Matcher::group);
        result = replace(result, PHONE, hits, "phone", Matcher::group);
        return new MaskedText(result, hits);
    }

    private static String replace(String text, Pattern pattern, Map<String, Integer> hits,
                                  String type, java.util.function.Function<Matcher, String> self) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            String raw = self.apply(matcher);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(maskValue(type, raw)));
            count++;
        }
        if (count == 0) {
            return text;
        }
        matcher.appendTail(sb);
        hits.merge(type, count, Integer::sum);
        return sb.toString();
    }

    private static String maskValue(String type, String raw) {
        if ("email".equals(type)) {
            int at = raw.indexOf('@');
            String local = raw.substring(0, at);
            String keep = local.length() <= 2 ? local.substring(0, Math.min(1, local.length())) : local.substring(0, 2);
            return keep + "***" + raw.substring(at);
        }
        if (raw.length() <= 8) {
            return "****";
        }
        return raw.substring(0, 4) + "*".repeat(raw.length() - 8) + raw.substring(raw.length() - 4);
    }

    /**
     * 脱敏结果：文本 + 各类型命中次数
     */
    public record MaskedText(String text, Map<String, Integer> hits) {

        public int total() {
            return hits.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
