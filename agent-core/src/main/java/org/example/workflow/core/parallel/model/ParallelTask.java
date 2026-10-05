package org.example.workflow.core.parallel.model;

import java.util.function.Supplier;

/**
 * 并行任务定义
 * <p>
 * 一个任务 = 名字 + 执行逻辑 + 兜底值 + 关键性标记。
 *
 * @param <T> 任务返回类型
 */
public record ParallelTask<T>(
        /** 任务名——用于日志和结果 key */
        String name,

        /** 任务逻辑（通常含 IO 调用） */
        Supplier<T> action,

        /**
         * 失败时的兜底值。
         * <p>
         * 为 null 表示"失败即弃"——不阻断整体，但该任务结果为空。
         */
        T fallback,

        /**
         * 是否关键任务。
         * <p>
         * true：失败会导致整个并行执行失败
         * false：失败被降级（用 fallback 或返回 null）
         */
        boolean critical,

        /**
         * 单任务超时（毫秒）。0 表示用全局默认。
         */
        long timeoutMs
) {
    /** 简化构造——非关键、无兜底、用默认超时 */
    public static <T> ParallelTask<T> of(String name, Supplier<T> action) {
        return new ParallelTask<>(name, action, null, false, 0);
    }

    /** 关键任务 */
    public static <T> ParallelTask<T> critical(String name, Supplier<T> action) {
        return new ParallelTask<>(name, action, null, true, 0);
    }

    /** 非关键 + 兜底值 */
    public static <T> ParallelTask<T> withFallback(String name,
                                                   Supplier<T> action,
                                                   T fallback) {
        return new ParallelTask<>(name, action, fallback, false, 0);
    }
}