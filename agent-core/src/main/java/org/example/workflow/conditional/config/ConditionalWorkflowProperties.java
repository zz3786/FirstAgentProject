package org.example.workflow.conditional.config;

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
@ConfigurationProperties(prefix = "app.conditional-workflow")
public class ConditionalWorkflowProperties {

    /** 总开关——测试时快速关闭 */
    private boolean enabled = true;

    /** 默认单步超时（毫秒） */
    private long defaultStepTimeoutMs = 10_000L;

    /** 整体超时（毫秒） */
    private long totalTimeoutMs = 60_000L;

    /** 幂等锁 TTL（秒）——防重复处理同一订单 */
    private int idempotencyLockSeconds = 300;

    /** 每个节点的独立超时覆盖（key = 节点名） */
    private Map<String, Long> stepTimeoutOverrides = new HashMap<>();

    /** 未知状态是否转人工——false 则直接失败 */
    private boolean escalateUnknownStatus = true;

    /**
     * 决策路由配置：
     *   key   = 订单状态（OrderStatus 的 name）
     *   value = 目标节点名
     * 支持 yml 覆盖——业务人员可调整分流规则
     */
    private Map<String, String> routingTable = new HashMap<>();

    /** 是否发送真实通知——false 时只打日志（干跑模式，方便测试） */
    private boolean dryRun = false;
}