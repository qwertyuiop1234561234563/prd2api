package com.dbappsecurity.prd2api.config;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.util.TraceIdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 鉴权拦截器
 * <p>
 * 当前实现：从网关注入的 Header 解析租户/用户/角色（X-Tenant-Id / X-User-Key / X-User-Role），
 * 预留对接企业 SSO：后续只需在此处替换为 token 解析即可，业务代码零改动。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_TENANT = "X-Tenant-Id";
    public static final String HEADER_USER = "X-User-Key";
    public static final String HEADER_ROLE = "X-User-Role";

    private static final String DEFAULT_TENANT = "default";
    private static final String DEFAULT_USER = "anonymous";

    /** 租户ID将参与本地存储目录拼接，必须严格限制字符集，防止路径穿越 */
    private static final Pattern TENANT_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern USER_PATTERN = Pattern.compile("^[A-Za-z0-9_.@:-]{1,64}$");

    private final ObjectMapper objectMapper;
    private final Prd2ApiProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = resolveIp(request);
        if (isIpBlocked(clientIp) || isPathBlocked(request.getRequestURI())) {
            writeJson(response, RespModel.fail(BizErrorCodeEnum.FORBIDDEN, "请求来源或接口已被禁用"));
            return false;
        }

        String traceId = TraceIdUtil.setTraceId(request.getHeader(TraceIdUtil.TRACE_HEADER));
        response.setHeader(TraceIdUtil.TRACE_HEADER, traceId);

        String rawTenant = request.getHeader(HEADER_TENANT);
        String rawUser = request.getHeader(HEADER_USER);
        if (properties.getSecurity().isRequireIdentity() && (StrUtil.isBlank(rawTenant) || StrUtil.isBlank(rawUser))) {
            writeJson(response, RespModel.fail(BizErrorCodeEnum.UNAUTHORIZED,
                    "缺少 X-Tenant-Id 或 X-User-Key"));
            return false;
        }
        String tenantId = StrUtil.blankToDefault(rawTenant, DEFAULT_TENANT);
        String userKey = StrUtil.blankToDefault(rawUser, DEFAULT_USER);
        if (!TENANT_PATTERN.matcher(tenantId).matches()) {
            writeJson(response, RespModel.fail(BizErrorCodeEnum.PARAM_INVALID,
                    "X-Tenant-Id 仅允许字母、数字、下划线、中划线，长度 1~64"));
            return false;
        }
        if (!USER_PATTERN.matcher(userKey).matches()) {
            writeJson(response, RespModel.fail(BizErrorCodeEnum.PARAM_INVALID,
                    "X-User-Key 仅允许字母、数字及 _ . @ : - ，长度 1~64"));
            return false;
        }
        String role = normalizeRole(request.getHeader(HEADER_ROLE));
        TenantContextUtil.set(tenantId, userKey, role);

        if (request.getRequestURI().startsWith("/api/admin") && !TenantContextUtil.isAdmin()) {
            writeJson(response, RespModel.fail(BizErrorCodeEnum.FORBIDDEN,
                    "管理端接口需要 X-User-Role: ADMIN"));
            return false;
        }
        return true;
    }

    /**
     * 角色白名单：仅 ADMIN / USER，其他值一律降级为普通用户，避免自定义角色越权
     */
    private String normalizeRole(String rawRole) {
        return TenantContextUtil.ADMIN_ROLE.equalsIgnoreCase(StrUtil.trim(rawRole))
                ? TenantContextUtil.ADMIN_ROLE
                : TenantContextUtil.USER_ROLE;
    }

    private boolean isIpBlocked(String clientIp) {
        return StrUtil.isNotBlank(clientIp) && properties.getSecurity().getIpBlacklist().contains(clientIp);
    }

    private boolean isPathBlocked(String uri) {
        for (String blocked : properties.getSecurity().getPathBlacklist()) {
            if (StrUtil.isNotBlank(blocked) && uri.startsWith(blocked)) {
                return true;
            }
        }
        return false;
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeJson(HttpServletResponse response, RespModel<?> body) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContextUtil.clear();
        TraceIdUtil.clear();
    }
}
