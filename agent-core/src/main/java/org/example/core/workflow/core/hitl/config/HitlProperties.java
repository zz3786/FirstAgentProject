package org.example.core.workflow.core.hitl.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * HITL 配置
 * <p>
 * yml 前缀：app.hitl.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.hitl")
public class HitlProperties {

    /** 总开关 */
    private boolean enabled = true;

    /** 默认超时（分钟）——超时后走兜底策略 */
    private long defaultTimeoutMinutes = 30;

    /** 超时任务的扫描间隔（毫秒） */
    private long scanIntervalMs = 60_000L;

    /** 默认处理人 */
    private String defaultAssignee = "admin";

    /** 是否启用自动超时扫描 */
    private boolean autoTimeoutEnabled = true;

    /**
     * 需要人工审批的分支白名单
     * <p>
     * 只有当订单工作流走的分支在这个集合里时，才挂起等审批。
     * 空 = 所有分支都审批（太重，不推荐）。
     */
    private Set<String> approvalRequiredBranches = Set.of(
            "PaymentReminderNode",           // 发送付款提醒——涉及对外通知
            "EscalateToHumanNode"             // 转人工——本来就要人看
    );

    /**
     * 需要人工审批的状态白名单
     * <p>
     * 状态维度也可以作为触发条件。
     */
    private Set<String> approvalRequiredStatuses = Set.of(
            "REFUNDING"    // 退款中——金额敏感
    );

    /** 超时后的兜底策略：REJECT / AUTO_APPROVE */
    private String timeoutFallback = "REJECT";

    /** 任务保存历史时长（小时） */
    private long taskHistoryTtlHours = 72;
}