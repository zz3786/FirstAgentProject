package org.example.core.toolprofile.config;

import lombok.Data;
import org.example.core.toolprofile.model.ToolProfile;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具画像配置（yml 前缀：{@code app.tools}）
 *
 * <h3>yml 结构</h3>
 * <pre>
 * app:
 *   tools:
 *     profiles:
 *       chat-default: {...}
 *       plan-execute: {...}
 *     consumers:
 *       chat-service: chat-default
 *       planner-service: plan-execute
 * </pre>
 *
 * <h3>两个 Map 的语义</h3>
 * <ul>
 *   <li>{@code profiles}——profile 名 → Profile 定义</li>
 *   <li>{@code consumers}——业务名 → profile 名</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.tools")
public class ToolProfileProperties {

    /** Profile 名 → Profile 定义 */
    private Map<String, ToolProfile> profiles = new HashMap<>();

    /** 业务名 → Profile 名 */
    private Map<String, String> consumers = new HashMap<>();
}