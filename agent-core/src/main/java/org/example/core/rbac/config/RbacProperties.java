package org.example.core.rbac.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * RBAC 权限配置（yml 前缀：{@code app.rbac}）
 *
 * <h3>两级校验</h3>
 * <ul>
 *   <li>{@code tool-requirements}——工具级：调这个工具需要什么密级</li>
 *   <li>{@code param-validation}——参数级：调这个工具时能不能传这个参数</li>
 * </ul>
 *
 * <h3>默认策略</h3>
 * <p>未在 {@code tool-requirements} 中声明的工具 → 默认密级 = 1（公开）。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "app.rbac")
public class RbacProperties {

    /** 总开关——关闭后所有校验都放行（仅本地调试用） */
    private boolean enabled = true;

    /** 工具名 → 权限要求 */
    private Map<String, ToolRequirement> toolRequirements = new HashMap<>();

    /** 参数级校验配置 */
    private ParamValidation paramValidation = new ParamValidation();

    // ==================== 嵌套配置 ====================

    /**
     * 单个工具的密级要求
     */
    @Data
    public static class ToolRequirement {
        /** 最低密级要求。null 表示不限制（等同 1） */
        private Integer minSecurityLevel;
    }

    /**
     * 参数级校验配置
     */
    @Data
    public static class ParamValidation {
        /**
         * 参数级校验开关。
         * <p>关闭后 {@code authorizeParams} 直接放行——用于排查问题。
         */
        private boolean enabled = true;
    }

    // ==================== 便捷方法 ====================

    /** 查询指定工具的最低密级要求——未配置时返回默认值 1 */
    public int resolveMinSecurityLevel(String toolName) {
        ToolRequirement req = toolRequirements.get(toolName);
        if (req == null || req.getMinSecurityLevel() == null) {
            return 1;
        }
        return req.getMinSecurityLevel();
    }
}