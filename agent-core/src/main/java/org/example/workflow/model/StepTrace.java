package org.example.workflow.model;

/**
 * 单步执行轨迹——用于可视化/审计
 */
public record StepTrace(
        String stepName,    // 步骤名
        String status,      // SUCCESS / FAILED / SKIPPED
        long costMs,        // 耗时
        String detail       // 输入/输出摘要
) {}