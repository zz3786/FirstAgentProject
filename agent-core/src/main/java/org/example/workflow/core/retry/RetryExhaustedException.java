package org.example.workflow.core.retry;

import org.example.workflow.core.retry.model.RetryStats;

/**
 * 重试耗尽异常
 * <p>
 * 所有重试尝试都失败后抛出——携带完整的重试统计。
 */
public class RetryExhaustedException extends RuntimeException {

    private final RetryStats stats;

    public RetryExhaustedException(RetryStats stats, Throwable lastCause) {
        super("重试耗尽: name=" + stats.name()
                        + ", attempts=" + stats.totalAttempts()
                        + ", costMs=" + stats.totalCostMs()
                        + ", lastError=" + stats.finalError(),
                lastCause);
        this.stats = stats;
    }

    public RetryStats getStats() {
        return stats;
    }
}