package org.example.core.rbac.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.core.rbac.ToolAuthorization;
import org.example.core.rbac.ToolAuthorizer;
import org.example.core.rbac.config.RbacProperties;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 基于密级 + 参数归属的 RBAC 实现（D69）
 *
 * <h3>两级判定</h3>
 * <ol>
 *   <li>{@link #authorizeTool}——工具级：用户密级 ≥ 工具要求密级</li>
 *   <li>{@link #authorizeParams}——参数级：入参指向的资源属于当前用户</li>
 * </ol>
 *
 * <h3>为什么不用 @Component</h3>
 * <p>
 * 本类由 {@code RbacBeanConfig} 显式装配为 Bean——
 * 便于在装配时打日志、便于换实现（如未来换成 "DB 驱动的权限表"）。
 * 用 @Component 也能工作，但会引入"两个 Bean"的风险——
 * 本类只声明一个 Bean 入口（RbacBeanConfig）。
 */
@Slf4j
public class RbacToolAuthorizer implements ToolAuthorizer {

    /** 未登录 / 未传密级时的兜底值 */
    private static final int DEFAULT_SECURITY_LEVEL = 1;

    private final RbacProperties properties;
    private final ParamOwnershipValidator paramValidator;

    public RbacToolAuthorizer(RbacProperties properties,
                              ParamOwnershipValidator paramValidator) {
        this.properties = properties;
        this.paramValidator = paramValidator;
        log.info("[D69] RbacToolAuthorizer 初始化：enabled={}, 已配置 {} 个工具的密级要求, paramValidation={}",
                properties.isEnabled(),
                properties.getToolRequirements().size(),
                properties.getParamValidation().isEnabled());
    }

    // ==================== 工具级 ====================

    @Override
    public ToolAuthorization authorizeTool(String toolName, ToolContext toolContext) {

        if (!properties.isEnabled()) {
            return ToolAuthorization.allow("RBAC 未启用");
        }

        int requiredLevel = properties.resolveMinSecurityLevel(toolName);
        int userLevel = extractUserSecurityLevel(toolContext);

        if (userLevel >= requiredLevel) {
            log.debug("[D69] 工具级鉴权通过: tool={}, required={}, user={}",
                    toolName, requiredLevel, userLevel);
            return ToolAuthorization.allow();
        }

        String reason = String.format(
                "工具 [%s] 要求最低密级 %d，当前用户密级 %d",
                toolName, requiredLevel, userLevel);
        log.warn("[D69] 工具级鉴权拒绝: {}", reason);
        return ToolAuthorization.deny(reason);
    }

    // ==================== 参数级 ====================

    @Override
    public ToolAuthorization authorizeParams(String toolName, String toolInput, ToolContext toolContext) {
        if (!properties.isEnabled()) {
            return ToolAuthorization.allow("RBAC 未启用");
        }
        if (!properties.getParamValidation().isEnabled()) {
            return ToolAuthorization.allow("参数级校验未启用");
        }
        return paramValidator.validate(toolName, toolInput, toolContext);
    }

    // ==================== 辅助 ====================

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