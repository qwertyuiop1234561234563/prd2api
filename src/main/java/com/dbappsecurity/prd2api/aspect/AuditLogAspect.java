package com.dbappsecurity.prd2api.aspect;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.annotation.Audit;
import com.dbappsecurity.prd2api.entity.AuditLog;
import com.dbappsecurity.prd2api.service.AuditLogService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.util.TraceIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作审计切面：拦截 {@link Audit} 注解方法，记录调用人、目标、耗时与结果
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogService auditLogService;

    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint joinPoint, Audit audit) throws Throwable {
        long start = System.currentTimeMillis();
        boolean success = true;
        String errorMsg = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            success = false;
            errorMsg = StrUtil.blankToDefault(throwable.getMessage(), throwable.getClass().getSimpleName());
            throw throwable;
        } finally {
            recordQuietly(joinPoint, audit, success, errorMsg, System.currentTimeMillis() - start);
        }
    }

    private void recordQuietly(ProceedingJoinPoint joinPoint, Audit audit,
                               boolean success, String errorMsg, long costMs) {
        try {
            AuditLog entity = new AuditLog();
            entity.setTenantId(TenantContextUtil.getTenantId());
            entity.setUserKey(TenantContextUtil.getUserKey());
            entity.setUserRole(TenantContextUtil.getRole());
            entity.setAction(audit.value());
            entity.setTarget(resolveTarget(joinPoint.getArgs()));
            entity.setSuccess(success ? 1 : 0);
            entity.setCostMs(costMs);
            entity.setTraceId(TraceIdUtil.getTraceId());
            entity.setErrorMsg(success ? null : errorMsg);

            HttpServletRequest request = currentRequest();
            if (request != null) {
                entity.setHttpMethod(request.getMethod());
                entity.setUri(request.getRequestURI());
                entity.setClientIp(resolveIp(request));
            }
            auditLogService.record(entity);
        } catch (Exception e) {
            log.warn("审计切面记录失败 action={}", audit.value(), e);
        }
    }

    /**
     * 默认取第一个字符串入参作为操作对象（通常是 taskId / fileMetaId）
     */
    private String resolveTarget(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof CharSequence text && StrUtil.isNotBlank(text)) {
                return text.toString();
            }
        }
        return null;
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
