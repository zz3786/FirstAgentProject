package org.example.core.rbac;

/**
 * 工具授权结果（D69）
 *
 * <h3>为什么不用 boolean</h3>
 * <p>boolean 无法携带"拒绝原因"。拒绝原因是安全审计的关键信息。
 */
public record ToolAuthorization(
        boolean allowed,
        String reason
) {

    public static ToolAuthorization allow() {
        return new ToolAuthorization(true, null);
    }

    public static ToolAuthorization allow(String reason) {
        return new ToolAuthorization(true, reason);
    }

    public static ToolAuthorization deny(String reason) {
        return new ToolAuthorization(false, reason);
    }
}