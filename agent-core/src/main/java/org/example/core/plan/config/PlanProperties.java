package org.example.core.plan.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Plan-and-Execute 配置
 * <p>
 * yml 前缀：app.plan.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.plan")
public class PlanProperties {

    /** 总开关 */
    private boolean enabled = true;

    // ========== 规划约束 ==========
    /** 计划最大步骤数——防止模型拆出 20 步 */
    private int maxSteps = 8;
    /** 单步描述最大长度——防止超长 prompt */
    private int maxDescriptionLength = 200;

    // ========== 执行约束 ==========
    /** 整体超时（毫秒）——超时后返回已完成部分 */
    private long totalTimeoutMs = 120_000L;
    /** 单步超时（毫秒） */
    private long perStepTimeoutMs = 30_000L;
    /** 单步重试次数（不含首次） */
    private int perStepRetry = 2;

    // ========== 重规划约束 ==========
    /** 最大重规划次数——防止无限循环 */
    private int maxReplans = 2;

    // ========== 工具白名单 ==========
    /**
     * @deprecated D69 起，Plan 工具白名单由 ToolProfile 管理。
     *             见 {@code app-tool-profiles.yml} 的 {@code plan-execute} profile。
     *             此字段保留仅为向后兼容——填了也不会生效。
     */
    @Deprecated
    private List<String> allowedTools = List.of();

    // ========== 状态持久化 ==========
    /** 是否持久化执行状态到 Redis——支持中断恢复 */
    private boolean persistState = true;
    /** 状态 TTL（小时） */
    private long stateTtlHours = 24;
}