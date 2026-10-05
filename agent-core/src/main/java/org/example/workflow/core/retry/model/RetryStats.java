package org.example.workflow.core.retry.model;

/**
 * 重试执行统计
 * <p>
 * 用于可观测性——把重试行为记录下来，便于分析故障模式。
 */
public record RetryStats(
        String name,           // 操作名
        int totalAttempts,     // 实际尝试次数
        int successAttempt,    // 第几次成功（1 表示首次即成功）
        long totalCostMs,      // 总耗时（含退避等待）
        long lastBackoffMs,    // 最后一次退避时长
        String finalError      // 最终失败原因（成功时为 null）
) {
    public boolean succeededFirstTry() {
        return successAttempt == 1;
    }

    public boolean succeededAfterRetry() {
        return successAttempt > 1 && finalError == null;
    }

    public boolean failed() {
        return finalError != null;
    }
}