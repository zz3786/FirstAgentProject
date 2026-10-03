package org.example.common.utils;

/**
 * D54 租户工具——纯逻辑，不依赖 Servlet
 */
public final class TenantUtils {

    private TenantUtils() {}

    public static final String DEFAULT_TENANT = "default";

    /**
     * 组装完整 userId —— 作为所有存储的隔离 key
     * <p>
     * 格式：{tenantId}:{userId}
     */
    public static String fullUserId(String tenantId, String userId) {
        if (userId == null) return null;
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = DEFAULT_TENANT;
        }
        return tenantId + ":" + userId;
    }
}