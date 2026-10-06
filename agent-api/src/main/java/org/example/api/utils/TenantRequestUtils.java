package org.example.api.utils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.example.common.utils.TenantUtils;

/**
 * D54 租户工具（Web 层）——从 HttpServletRequest 拿租户信息
 * <p>
 * 放在 agent-api 而不是 agent-common——避免污染底层模块依赖。
 */
public final class TenantRequestUtils {

    private TenantRequestUtils() {}

    /** 从 Session 取 tenantId */
    public static String getTenantId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return TenantUtils.DEFAULT_TENANT;
        }
        Object tid = session.getAttribute("AUTH_TENANT_ID");
        return tid == null ? TenantUtils.DEFAULT_TENANT : tid.toString();
    }

    /** 一步到位——完整 userId */
    public static String fullUserId(HttpServletRequest request) {
        return TenantUtils.fullUserId(
                getTenantId(request),
                SessionUtils.getUserId(request));
    }
}