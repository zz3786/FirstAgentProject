package org.example.core.toolbootstrap;

/**
 * 工具刷新结果（D68）
 *
 * <h3>为什么静态工厂方法用 ok/skip/fail 而不是 success/skipped/failed</h3>
 * <p>
 * 本类是 record——编译器会为每个字段自动生成同名 accessor：
 * <pre>
 *   boolean success()   // 自动生成
 *   boolean skipped()   // 自动生成
 *   String error()      // 自动生成
 * </pre>
 * <p>
 * 如果静态工厂方法也叫 {@code success()} / {@code skipped()} / {@code failed()}，
 * 就会和 accessor 同名同参——Java 不允许静态方法与实例方法重名。
 * <p>
 * 因此静态工厂统一用 {@code ok} / {@code skip} / {@code fail}——
 * 简短、不与任何字段名冲突、语义清晰。
 *
 * <h3>字段语义</h3>
 * <ul>
 *   <li>{@code success}——刷新是否成功完成</li>
 *   <li>{@code skipped}——是否因为"已有刷新在执行"而被跳过（幂等提示）</li>
 *   <li>{@code beforeCount}——刷新前的工具总数</li>
 *   <li>{@code afterCount}——刷新后的工具总数</li>
 *   <li>{@code localCount}——刷新后本地工具数</li>
 *   <li>{@code mcpCount}——刷新后 MCP 远端工具数</li>
 *   <li>{@code costMs}——本次刷新耗时（毫秒）</li>
 *   <li>{@code error}——失败时的错误信息；成功时为 null</li>
 * </ul>
 */
public record ToolRefreshResult(
        boolean success,
        boolean skipped,
        int beforeCount,
        int afterCount,
        int localCount,
        int mcpCount,
        long costMs,
        String error
) {

    /**
     * 成功——记录刷新前后对比数据。
     * <p>方法名用 {@code ok} 而不是 {@code success}——后者与字段 accessor 冲突。
     */
    public static ToolRefreshResult ok(int before, int after,
                                       int local, int mcp, long cost) {
        return new ToolRefreshResult(true, false, before, after, local, mcp, cost, null);
    }

    /**
     * 跳过——已有刷新在执行中。
     * <p>方法名用 {@code skip} 而不是 {@code skipped}——后者与字段 accessor 冲突。
     */
    public static ToolRefreshResult skip() {
        return new ToolRefreshResult(false, true, 0, 0, 0, 0, 0,
                "已有刷新在执行中");
    }

    /**
     * 失败——携带错误信息。
     * <p>方法名用 {@code fail} 而不是 {@code failed}——避免与未来可能加的
     * "是否有失败" 字段 accessor 冲突（当前没有，但保持一致性）。
     */
    public static ToolRefreshResult fail(String error) {
        return new ToolRefreshResult(false, false, 0, 0, 0, 0, 0, error);
    }
}