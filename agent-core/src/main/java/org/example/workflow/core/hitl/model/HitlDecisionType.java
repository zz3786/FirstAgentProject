package org.example.workflow.core.hitl.model;

/**
 * 人工决策类型
 */
public enum HitlDecisionType {
    /** 批准——继续执行 */
    APPROVE,
    /** 拒绝——中止执行 */
    REJECT,
    /** 修改——用人工修改后的数据继续 */
    MODIFY
}