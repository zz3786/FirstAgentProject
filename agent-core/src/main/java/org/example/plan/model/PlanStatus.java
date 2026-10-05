package org.example.plan.model;

/**
 * 工作流状态
 */
public enum PlanStatus {
    PLANNING,       // 规划中
    EXECUTING,      // 执行中
    REPLANNING,     // 重规划中
    SYNTHESIZING,   // 汇总中
    SUCCESS,        // 成功完成
    FAILED,         // 失败
    TIMEOUT         // 超时
}