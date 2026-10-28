package com.dbappsecurity.prd2api.util;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import lombok.Data;

/**
 * 租户 + 用户上下文（ThreadLocal）
 * <p>
 * HTTP 请求由 AuthInterceptor 写入（登录态优先，其次 Header 透传）；
 * 异步任务由 AgentServiceImpl 在任务线程内重新写入，结束后清理。
 */
public final class TenantContextUtil {

    public static final String ADMIN_ROLE = "ADMIN";
    public static final String USER_ROLE = "USER";

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    private TenantContextUtil() {
    }

    public static void set(String tenantId, String userKey, String role) {
        HOLDER.set(new UserContext(null, tenantId, userKey, role));
    }

    public static void set(String userId, String tenantId, String userKey, String role) {
        HOLDER.set(new UserContext(userId, tenantId, userKey, role));
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static String getUserId() {
        UserContext context = HOLDER.get();
        return context == null ? null : context.getUserId();
    }

    public static String requireUserId() {
        String userId = getUserId();
        if (StrUtil.isBlank(userId)) {
            throw new BizException(BizErrorCodeEnum.UNAUTHORIZED, "当前请求未绑定登录用户，请先登录");
        }
        return userId;
    }

    public static String getTenantId() {
        UserContext context = HOLDER.get();
        return context == null ? null : context.getTenantId();
    }

    public static String requireTenantId() {
        String tenantId = getTenantId();
        if (StrUtil.isBlank(tenantId)) {
            throw new BizException(BizErrorCodeEnum.UNAUTHORIZED, "缺少租户上下文，请通过 X-Tenant-Id 传入");
        }
        return tenantId;
    }

    public static String getUserKey() {
        UserContext context = HOLDER.get();
        return context == null ? null : context.getUserKey();
    }

    public static String requireUserKey() {
        String userKey = getUserKey();
        if (StrUtil.isBlank(userKey)) {
            throw new BizException(BizErrorCodeEnum.UNAUTHORIZED, "缺少用户上下文，请通过 X-User-Key 传入");
        }
        return userKey;
    }

    public static String getRole() {
        UserContext context = HOLDER.get();
        return context == null ? USER_ROLE : StrUtil.blankToDefault(context.getRole(), USER_ROLE);
    }

    public static boolean isAdmin() {
        return ADMIN_ROLE.equalsIgnoreCase(getRole());
    }

    @Data
    public static class UserContext {
        private String userId;
        private String tenantId;
        private String userKey;
        private String role;

        public UserContext(String userId, String tenantId, String userKey, String role) {
            this.userId = userId;
            this.tenantId = tenantId;
            this.userKey = userKey;
            this.role = role;
        }
    }
}
