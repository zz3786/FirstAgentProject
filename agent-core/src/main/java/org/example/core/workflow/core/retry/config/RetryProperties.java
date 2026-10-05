package org.example.core.workflow.core.retry.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 重试与降级配置
 * <p>
 * yml 前缀：app.retry.*
 * <p>
 * <b>设计原则</b>：
 * <ul>
 *   <li>默认值保守——不要默认疯狂重试</li>
 *   <li>每个数据源可单独覆盖——不同外部依赖容忍度不同</li>
 *   <li>总开关可关——出问题能一键退化为"不重试"</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.retry")
public class RetryProperties {

    /** 总开关——关掉后所有重试退化为"只执行一次" */
    private boolean enabled = true;

    /** 全局默认最大尝试次数（含首次） */
    private int defaultMaxAttempts = 3;

    /** 全局默认初始退避（毫秒） */
    private long defaultBackoffMs = 200L;

    /** 全局默认退避倍数——指数退避时用 */
    private double defaultBackoffMultiplier = 2.0;

    /** 全局默认最大退避（毫秒）——防止退避时间无限增长 */
    private long defaultMaxBackoffMs = 5000L;

    /** 全局默认是否加随机抖动——防止"惊群" */
    private boolean defaultJitter = true;

    /**
     * 按数据源名覆盖配置
     * <p>
     * key = 数据源名（对应 ParallelTask 的 name）
     * value = 该数据源的独立配置
     * <p>
     * 示例：
     * <pre>
     * app:
     *   retry:
     *     override:
     *       logistics:
     *         max-attempts: 5
     *         backoff-ms: 500
     *       user-profile:
     *         max-attempts: 2
     * </pre>
     */
    private Map<String, SourceConfig> override = new HashMap<>();

    /** 单个数据源的重试配置 */
    @Data
    public static class SourceConfig {
        private Integer maxAttempts;
        private Long backoffMs;
        private Double backoffMultiplier;
        private Long maxBackoffMs;
        private Boolean jitter;
    }
}