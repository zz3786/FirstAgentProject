package org.example.core.workflow.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "app.workflow.engine")
public class WorkflowEngineProperties {
    private boolean enabled = true;
    private long defaultStepTimeoutMs = 10_000L;
    private long totalTimeoutMs = 60_000L;
    private int idempotencyLockSeconds = 300;
    private Map<String, Long> stepTimeoutOverrides = new HashMap<>();
    private boolean dryRun = false;
}
