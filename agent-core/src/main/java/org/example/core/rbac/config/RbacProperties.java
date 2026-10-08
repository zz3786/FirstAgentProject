package org.example.core.rbac.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * RBAC 权限配置（yml 前缀：{@code app.rbac}）
 *
 * <h3>默认策略</h3>
 * <p>未在 {@code tool-requirements} 中声明的工具 → 默认密级 = 1（公开）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.rbac")
public class RbacProperties {

    /** 总开关——关闭后不做鉴权（仅本地调试） */
    private boolean enabled = true;

    /** 工具名 → 权限要求 */
    private Map<String, ToolRequirement> toolRequirements = new HashMap<>();

    @Data
    public static class ToolRequirement {
        /** 最低密级要求。null 表示不限制（等同 1） */
        private Integer minSecurityLevel;
    }

    /** 查询指定工具的最低密级要求——未配置时返回默认值 1 */
    public int resolveMinSecurityLevel(String toolName) {
        ToolRequirement req = toolRequirements.get(toolName);
        if (req == null || req.getMinSecurityLevel() == null) {
            return 1;
        }
        return req.getMinSecurityLevel();
    }
}