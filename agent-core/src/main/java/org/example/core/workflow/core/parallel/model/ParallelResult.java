package org.example.core.workflow.core.parallel.model;

import java.util.Map;

/**
 * 并行执行结果
 * <p>
 * 包含：
 * <ul>
 *   <li>每个任务的结果（按 name 索引）</li>
 *   <li>每个任务的执行状态</li>
 *   <li>总耗时</li>
 *   <li>是否有失败</li>
 * </ul>
 */
public record ParallelResult<T>(
        /** 任务名 → 结果 */
        Map<String, T> results,

        /** 任务名 → 是否成功 */
        Map<String, Boolean> successFlags,

        /** 任务名 → 错误信息（仅失败任务有） */
        Map<String, String> errors,

        /** 总耗时（毫秒） */
        long totalCostMs,

        /** 是否所有关键任务都成功 */
        boolean allCriticalSucceeded
) {

    /** 按名字取结果 */
    public T get(String taskName) {
        return results.get(taskName);
    }

    /** 取结果——带默认值 */
    public T getOrDefault(String taskName, T defaultValue) {
        return results.getOrDefault(taskName, defaultValue);
    }

    /** 某任务是否成功 */
    public boolean isSuccess(String taskName) {
        return Boolean.TRUE.equals(successFlags.get(taskName));
    }
}