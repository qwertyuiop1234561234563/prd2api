package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * 全链路 traceId 工具：基于 MDC，日志 pattern 中通过 %X{traceId} 输出
 */
public final class TraceIdUtil {

    public static final String TRACE_ID = "traceId";
    public static final String TRACE_HEADER = "X-Trace-Id";

    private TraceIdUtil() {
    }

    public static String getTraceId() {
        return StrUtil.blankToDefault(MDC.get(TRACE_ID), "-");
    }

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 设置 traceId，返回实际生效的 traceId
     */
    public static String setTraceId(String traceId) {
        String value = StrUtil.isBlank(traceId) ? generate() : traceId;
        MDC.put(TRACE_ID, value);
        return value;
    }

    public static void clear() {
        MDC.remove(TRACE_ID);
    }
}
