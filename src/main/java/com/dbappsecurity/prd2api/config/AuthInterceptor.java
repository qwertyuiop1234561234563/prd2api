package com.dbappsecurity.prd2api.config;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.common.RespModel;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.entity.SysUser;
import com.dbappsecurity.prd2api.service.AuthService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.util.TraceIdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 鉴权拦截器
 * <p>
 * 优先解析 Authorization: Bearer 登录令牌；未携带令牌时回退到网关注入的 Header
 * （X-Tenant-Id / X-User-Key / X-User-Role），便于内部脚本与运维调用。
 * 开启 prd2api.security.require-login 后仅接受登录态。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_TENANT = "X-Tenant-Id";
    public static final String HEADER_USER = "X-User-Key";
    public static final String HEADER_ROLE = "X-User-Role";
    public static final String HEADER_AUTH = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String DEFAULT_TENANT = "default";
    private static final String DEFAULT_USER = "anonymous";
    /** 管理端接口前缀 */
    private static final String ADMIN_PATH = "/api/admin";
    /**
     * 知识库接口前缀：离线文档入库、启停、重建会直接影响所有对话的召回结果，
     * 属于全局管理能力，与 /api/admin 一样仅管理员可访问
     */
    private static final String KNOWLEDGE_ADMIN_PATH = "/api/knowledge";

    /** 租户ID将参与本地存储目录拼接，必须严格限制字符集，防止路径穿越 */
    private static final Pattern TENANT_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern USER_PATTERN = Pattern.compile("^[A-Za-z0-9_.@:-]{1,64}$");

    private final ObjectMapper objectMapper;
    private final Prd2ApiProperties properties;
    private final AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 跨域预检请求不携带 Authorization 与业务 Header，若在此处鉴权会被判为匿名用户而 403，
        // 浏览器随即拦截真正的请求，因此预检必须直接放行（由 CORS 配置返回允许头）
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String clientIp = resolveIp(request);
        if (isIpBlocked(clientIp) || isPathBlocked(request.getRequestURI())) {
            writeJson(response, RespModel.fail(BizErrorCodeEnum.FORBIDDEN, "请求来源或接口已被禁用"));
            return false;
        }

        String traceId = TraceIdUtil.setTraceId(request.getHeader(TraceIdUtil.TRACE_HEADER));
        response.setHeader(TraceIdUtil.TRACE_HEADER, traceId);

        String token = resolveToken(request);
        if (StrUtil.isNotBlank(token)) {
            SysUser user = authService.resolveToken(token);
            if (user == null) {
                writeJson(response, RespModel.fail(BizErrorCodeEnum.UNAUTHORIZED, "登录状态已失效，请重新登录"));
                return false;
            }
            TenantContextUtil.set(user.getUserId(), user.getTenantId(), user.getUsername(),
                    normalizeRole(user.getRole()));
        } else {
            if (properties.getSecurity().isRequireLogin()) {
                writeJson(response, RespModel.fail(BizErrorCodeEnum.UNAUTHORIZED, "该接口需要登录后访问"));
                return false;
            }
            if (!bindHeaderIdentity(request, response)) {
                return false;
            }
        }

        String uri = request.getRequestURI();
        if (!TenantContextUtil.isAdmin()) {
            if (uri.startsWith(ADMIN_PATH)) {
                writeJson(response, RespModel.fail(BizErrorCodeEnum.FORBIDDEN,
                        "管理端接口需要 ADMIN 角色"));
                return false;
            }
            if (uri.startsWith(KNOWLEDGE_ADMIN_PATH)) {
                writeJson(response, RespModel.fail(BizErrorCodeEnum.FORBIDDEN,
                        "知识库仅管理员可访问"));
                return false;
            }
        }
        return true;
    }

    /**
     * Header 身份透传模式：校验租户/用户字符集，避免路径穿越与非法标识
     */
    private boolean bindHeaderIdentity(HttpServletRequest request, HttpServletResponse response) throws Exception {
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
        return true;
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_AUTH);
        if (StrUtil.isBlank(header) || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return StrUtil.trim(header.substring(BEARER_PREFIX.length()));
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
        if (properties.getSecurity().isTrustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StrUtil.isNotBlank(forwarded)) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private void writeJson(HttpServletResponse response, RespModel<?> body) throws Exception {
        // 与全局异常处理保持一致：业务码同步映射到 HTTP 状态码，便于网关、监控与客户端识别失败
        response.setStatus(RespModel.toHttpStatus(body.getCode()));
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
