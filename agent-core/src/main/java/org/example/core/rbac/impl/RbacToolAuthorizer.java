package org.example.core.rbac.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.core.rbac.ToolAuthorization;
import org.example.core.rbac.ToolAuthorizer;
import org.example.core.rbac.config.RbacProperties;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * 基于密级的 RBAC 实现（D69）
 *
 * <h3>判定规则</h3>
 * <pre>
 *   用户密级 ≥ 工具要求的最低密级  →  允许
 *   用户密级 &lt; 工具要求的最低密级  →  拒绝
 * </pre>
 */
@Slf4j
@Component
public class RbacToolAuthorizer implements ToolAuthorizer {

    private static final int DEFAULT_SECURITY_LEVEL = 1;

    private final RbacProperties properties;

    public RbacToolAuthorizer(RbacProperties properties) {
        this.properties = properties;
        log.info("[D69] RbacToolAuthorizer 初始化：enabled={}, 已配置 {} 个工具的密级要求",
                properties.isEnabled(),
                properties.getToolRequirements().size());
    }

    @Override
    public ToolAuthorization authorizeTool(String toolName, ToolContext toolContext) {

        if (!properties.isEnabled()) {
            return ToolAuthorization.allow("RBAC 未启用");
        }

        int requiredLevel = properties.resolveMinSecurityLevel(toolName);
        int userLevel = extractUserSecurityLevel(toolContext);

        if (userLevel >= requiredLevel) {
            log.debug("[D69] 鉴权通过: tool={}, required={}, user={}",
                    toolName, requiredLevel, userLevel);
            return ToolAuthorization.allow();
        }

        String reason = String.format(
                "工具 [%s] 要求最低密级 %d，当前用户密级 %d",
                toolName, requiredLevel, userLevel);
        log.warn("[D69] 鉴权拒绝: {}", reason);
        return ToolAuthorization.deny(reason);
    }

    private int extractUserSecurityLevel(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return DEFAULT_SECURITY_LEVEL;
        }
        Object v = toolContext.getContext().get("securityLevel");
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString());
            } catch (NumberFormatException ignore) {
                // fallthrough
            }
        }
        return DEFAULT_SECURITY_LEVEL;
    }
}