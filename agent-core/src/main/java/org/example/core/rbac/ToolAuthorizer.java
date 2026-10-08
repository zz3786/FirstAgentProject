package org.example.core.rbac;

import org.springframework.ai.chat.model.ToolContext;

/**
 * 工具授权器（D69）
 *
 * <h3>两层校验</h3>
 * <ul>
 *   <li>{@link #authorizeTool}——工具级：能不能调这个工具（D69 第一轮）</li>
 *   <li>{@link #authorizeParams}——参数级：能不能传这个参数（D69 第二轮）</li>
 * </ul>
 */
public interface ToolAuthorizer {

    /**
     * 工具级鉴权
     */
    ToolAuthorization authorizeTool(String toolName, ToolContext toolContext);

    /**
     * 参数级鉴权——D69 第一轮默认放行
     */
    default ToolAuthorization authorizeParams(String toolName,
                                              String toolInput,
                                              ToolContext toolContext) {
        return ToolAuthorization.allow();
    }
}