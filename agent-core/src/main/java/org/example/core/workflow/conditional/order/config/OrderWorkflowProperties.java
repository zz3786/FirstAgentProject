package org.example.core.workflow.conditional.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 条件工作流配置
 * <p>
 * yml 前缀：app.conditional-workflow.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.workflow.order")
public class OrderWorkflowProperties {
    private boolean escalateUnknownStatus = true;
    private Map<String, String> routingTable = new HashMap<>();
}