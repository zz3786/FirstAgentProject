package org.example.workflow.core.hitl.model;

/**
 * 挂起任务状态
 */
public enum HitlStatus {
    /** 等待人工处理 */
    PENDING,
    /** 已批准 */
    APPROVED,
    /** 已拒绝 */
    REJECTED,
    /** 已修改——用人工修改后的数据继续 */
    MODIFIED,
    /** 超时——走兜底策略 */
    TIMEOUT,
    /** 已取消 */
    CANCELLED;

    /** 是否是终态 */
    public boolean isTerminal() {
        return this != PENDING;
    }
}