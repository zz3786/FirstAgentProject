package org.example.workflow.core.hitl.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HITL 挂起任务——持久化到 Redis 的完整对象
 */
@Data
@NoArgsConstructor
public class HitlTask {

    /** 任务 ID——全局唯一 */
    private String taskId;

    /** 状态 */
    private HitlStatus status = HitlStatus.PENDING;

    /** 挂起原因（机器视角） */
    private String reason;

    /** 给人工看的提示——比 reason 更友好 */
    private String humanPrompt;

    /** 上下文快照 */
    private HitlContext context;

    /** 处理人——可指定或从队列认领 */
    private String assignee;

    /** 创建时间 */
    private long createdAt;

    /** 超时时间 */
    private long timeoutAt;

    /** 决策记录（决策后填充） */
    private HitlDecision decision;
}