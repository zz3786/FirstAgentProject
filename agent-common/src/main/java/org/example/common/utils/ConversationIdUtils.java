package org.example.common.utils;

/**
 * D54：conversationId 工具
 * <p>
 * <b>conversationId 格式</b>：
 * <pre>
 * {tenantId}:{userId}:{sessionTag}
 * 例：hospital-a:user-alice:sess-1
 * </pre>
 * <p>
 * <b>为什么单独抽类</b>：
 * 5 个 Advisor + ChatService 都要"从 conversationId 提取 userId"——
 * 集中一处便于维护、避免各自实现不一致。
 */
public final class ConversationIdUtils {

    /** 默认会话标签 */
    public static final String DEFAULT_SESSION = "default";

    /** sessionTag 合法字符——防止 `:` 注入破坏结构 */
    private static final String SESSION_TAG_PATTERN = "[a-zA-Z0-9_-]+";

    private ConversationIdUtils() {}

    // ==================== 组装 ====================

    /**
     * 组装 conversationId
     *
     * @param fullUserId 完整用户 ID（{tenantId}:{userId}）
     * @param sessionTag 会话标签（可为空 → "default"）
     */
    public static String build(String fullUserId, String sessionTag) {
        if (fullUserId == null) {
            fullUserId = "default";
        }
        String tag = sanitizeSessionTag(sessionTag);
        return fullUserId + ":" + tag;
    }

    /**
     * 校验 sessionTag——非法字符替换成 "default"
     * <p>
     * 防止前端传 `sess:1` 破坏 `{tenantId}:{userId}:{sessionTag}` 结构。
     */
    public static String sanitizeSessionTag(String sessionTag) {
        if (sessionTag == null || sessionTag.isBlank()) {
            return DEFAULT_SESSION;
        }
        if (!sessionTag.matches(SESSION_TAG_PATTERN)) {
            return DEFAULT_SESSION;
        }
        return sessionTag;
    }

    // ==================== 拆解 ====================

    /**
     * 从 conversationId 提取完整 userId
     * <p>
     * <b>关键</b>：用 lastIndexOf——因为 userId 可能含 `:`（租户前缀）。
     * <pre>
     * "hospital-a:user-alice:sess-1"  →  "hospital-a:user-alice"
     * "user-alice:sess-1"             →  "user-alice"
     * "user-alice"                    →  "user-alice"
     * </pre>
     */
    public static String extractFullUserId(String conversationId) {
        if (conversationId == null) {
            return "default";
        }
        int lastColon = conversationId.lastIndexOf(':');
        return lastColon > 0
                ? conversationId.substring(0, lastColon)
                : conversationId;
    }

    /**
     * 从 conversationId 提取 sessionTag（最后一段）
     */
    public static String extractSessionTag(String conversationId) {
        if (conversationId == null) {
            return DEFAULT_SESSION;
        }
        int lastColon = conversationId.lastIndexOf(':');
        return lastColon > 0
                ? conversationId.substring(lastColon + 1)
                : DEFAULT_SESSION;
    }

    /**
     * 从 conversationId 提取 tenantId
     * <p>
     * 格式：{tenantId}:{userId}:{sessionTag}
     */
    public static String extractTenantId(String conversationId) {
        if (conversationId == null) {
            return TenantUtils.DEFAULT_TENANT;
        }
        int firstColon = conversationId.indexOf(':');
        return firstColon > 0
                ? conversationId.substring(0, firstColon)
                : TenantUtils.DEFAULT_TENANT;
    }
}