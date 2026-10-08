package org.example.core.rbac.wrapper;

import lombok.extern.slf4j.Slf4j;
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
 * <h3>拒绝时行为</h3>
 * <p>返回友好文本给 LLM，不抛异常——让 LLM 用自然语言回复用户。
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

    @Override
    public String call(String toolInput) {
        String toolName = delegate.getToolDefinition().name();
        ToolContext emptyContext = new ToolContext(java.util.Map.of());

        ToolAuthorization auth = authorizer.authorizeTool(toolName, emptyContext);
        if (!auth.allowed()) {
            return buildDenyMessage();
        }
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();

        // ① 工具级鉴权
        ToolAuthorization toolAuth = authorizer.authorizeTool(toolName, toolContext);
        if (!toolAuth.allowed()) {
            log.warn("[D69] 工具级鉴权拒绝: tool={}, reason={}",
                    toolName, toolAuth.reason());
            return buildDenyMessage();
        }

        // ② 参数级鉴权（D69 第一轮默认放行）
        ToolAuthorization paramAuth = authorizer.authorizeParams(
                toolName, toolInput, toolContext);
        if (!paramAuth.allowed()) {
            log.warn("[D69] 参数级鉴权拒绝: tool={}, reason={}",
                    toolName, paramAuth.reason());
            return buildDenyMessage();
        }

        // ③ 委托给下游
        return delegate.call(toolInput, toolContext);
    }

    private String buildDenyMessage() {
        return "⚠️ 您没有权限执行该操作。如需使用，请联系管理员为您的账号开通相应权限。";
    }
}