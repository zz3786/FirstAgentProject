package org.example.core.rbac.wrapper;

import lombok.extern.slf4j.Slf4j;
import org.example.common.audit.AuditLogger;
import org.example.core.rbac.ToolAuthorization;
import org.example.core.rbac.ToolAuthorizer;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 鉴权工具回调——在 SafeToolCallback 外层再包一层（D69）
 *
 * <h3>包装层次</h3>
 * <pre>
 *   ChatClient → AuthzToolCallback → SafeToolCallback → 工具
 * </pre>
 *
 * <h3>D69 三轮演进</h3>
 * <ul>
 *   <li><b>第一轮</b>——工具级鉴权（用户密级 vs 工具要求密级）</li>
 *   <li><b>第二轮</b>——参数级鉴权（数据归属）</li>
 *   <li><b>第三轮</b>——审计日志接入（拒绝时写 AUDIT 日志）</li>
 * </ul>
 *
 * <h3>审计策略</h3>
 * <p>
 * <b>只审计拒绝，不审计通过</b>：
 * <ul>
 *   <li>拒绝 = 安全事件，需要 WARN 级 + 独立审计日志文件</li>
 *   <li>通过 = 正常业务，用 DEBUG 级即可（不算审计）</li>
 * </ul>
 * <p>
 * 若运维需要"全量调用审计"，可以调 DEBUG 级别日志——
 * 但那属于"业务日志"而非"安全审计"。
 *
 * <h3>拒绝时行为</h3>
 * <p>
 * 返回友好文本给 LLM，不抛异常——让 LLM 用自然语言回复用户。
 */
@Slf4j
public class AuthzToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolAuthorizer authorizer;

    public AuthzToolCallback(ToolCallback delegate, ToolAuthorizer authorizer) {
        this.delegate = delegate;
        this.authorizer = authorizer;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    // ==================== 无 ToolContext ====================

    @Override
    public String call(String toolInput) {
        String toolName = delegate.getToolDefinition().name();
        ToolContext emptyContext = new ToolContext(java.util.Map.of());

        ToolAuthorization auth = authorizer.authorizeTool(toolName, emptyContext);
        if (!auth.allowed()) {
            log.warn("[D69] 工具级鉴权拒绝: tool={}, reason={}",
                    toolName, auth.reason());
            // ★ D69 第三轮：写审计日志（无 userId 信息）
            AuditLogger.authzDeny("TOOL", toolName, "<anonymous>", auth.reason());
            return buildDenyMessage("操作");
        }
        return delegate.call(toolInput);
    }

    // ==================== 带 ToolContext ====================

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        String fullUserId = extractUserId(toolContext);

        // ── ① 工具级鉴权
        ToolAuthorization toolAuth = authorizer.authorizeTool(toolName, toolContext);
        if (!toolAuth.allowed()) {
            log.warn("[D69] 工具级鉴权拒绝: tool={}, reason={}",
                    toolName, toolAuth.reason());
            // ★ D69 第三轮：写审计日志
            AuditLogger.authzDeny("TOOL", toolName, fullUserId, toolAuth.reason());
            return buildDenyMessage("操作");
        }

        // ── ② 参数级鉴权
        ToolAuthorization paramAuth = authorizer.authorizeParams(
                toolName, toolInput, toolContext);
        if (!paramAuth.allowed()) {
            log.warn("[D69] 参数级鉴权拒绝: tool={}, reason={}",
                    toolName, paramAuth.reason());
            // ★ D69 第三轮：写审计日志（参数级——安全等级更高）
            AuditLogger.authzDeny("PARAM", toolName, fullUserId, paramAuth.reason());
            return buildDenyMessage("数据");
        }

        // ── ③ 鉴权通过——DEBUG 级业务日志（不算审计）
        if (log.isDebugEnabled()) {
            log.debug("[D69] 鉴权通过: tool={}, userId={}", toolName, fullUserId);
        }
        return delegate.call(toolInput, toolContext);
    }

    // ==================== 辅助 ====================

    /**
     * 从 ToolContext 提取 userId——用于审计日志。
     * <p>取不到时返回 {@code "<unknown>"}——审计日志不能因为缺信息就不写。
     */
    private String extractUserId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return "<unknown>";
        }
        Object v = toolContext.getContext().get("userId");
        return v == null ? "<unknown>" : v.toString();
    }

    /**
     * 构造拒绝提示——返回给 LLM 的文本。
     *
     * @param scope "操作"（工具级拒绝）或 "数据"（参数级拒绝）——
     *              让用户能区分"你没权限用这个功能" vs "你没权限看这条数据"
     */
    private String buildDenyMessage(String scope) {
        return String.format(
                "⚠️ 您没有权限访问该%s。如需使用，请联系管理员为您的账号开通相应权限。",
                scope);
    }
}