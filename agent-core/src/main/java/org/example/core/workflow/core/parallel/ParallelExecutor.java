package org.example.core.workflow.core.parallel;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.core.parallel.config.ParallelProperties;
import org.example.core.workflow.core.parallel.model.ParallelResult;
import org.example.core.workflow.core.parallel.model.ParallelTask;
import org.example.core.retry.RetryExhaustedException;
import org.example.core.retry.RetryPredicates;
import org.example.core.retry.AiRetryTemplate;
import org.example.core.retry.model.RetryPolicy;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 通用并行执行器
 * <p>
 * <b>职责</b>：把 N 个任务并发提交，等待全部完成（或超时），返回聚合结果。
 * <p>
 * <b>关键设计</b>：
 * <ul>
 *   <li>虚拟线程池（Java 21）——IO 密集场景大量并发不会爆内存</li>
 *   <li>单任务超时 + 整体超时——双层保护</li>
 *   <li>关键任务失败 → 整体失败；非关键失败 → 降级</li>
 *   <li>返回结构化结果，包含每个任务的状态</li>
 * </ul>
 */
@Slf4j
@Component
public class ParallelExecutor {

    private final AiRetryTemplate workflowRetryTemplate;
    private final ParallelProperties props;
    private final ExecutorService executor;

    public ParallelExecutor(AiRetryTemplate workflowRetryTemplate, ParallelProperties props) {
        this.workflowRetryTemplate = workflowRetryTemplate;
        this.props = props;
        this.executor = createExecutor(props);
        log.info("[ParallelExecutor] 初始化: virtualThreads={}, timeoutMs={}",
                props.isUseVirtualThreads(), props.getDefaultTimeoutMs());
    }

    private ExecutorService createExecutor(ParallelProperties p) {
        if (p.isUseVirtualThreads()) {
            // ★ Java 21 虚拟线程——IO 密集场景推荐
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        // 平台线程池——CPU 密集或需要精细控制
        return new ThreadPoolExecutor(
                p.getPlatformPoolSize(),
                p.getPlatformPoolSize(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(p.getPlatformQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "parallel-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 并行执行所有任务
     * <p>
     * 语义：
     * <ul>
     *   <li>所有任务同时启动</li>
     *   <li>等待所有完成或整体超时</li>
     *   <li>关键任务失败 → allCriticalSucceeded=false</li>
     *   <li>非关键失败 → 用 fallback，不阻断</li>
     * </ul>
     */
    public <T> ParallelResult<T> executeAll(List<ParallelTask<T>> tasks) {
        return executeAll(tasks, props.getDefaultTimeoutMs());
    }

    public <T> ParallelResult<T> executeAll(List<ParallelTask<T>> tasks, long totalTimeoutMs) {
        if (tasks == null || tasks.isEmpty()) {
            return new ParallelResult<>(Map.of(), Map.of(), Map.of(), 0L, true);
        }

        // 关闭开关时退化为串行
        if (!props.isEnabled()) {
            return executeSequentially(tasks);
        }

        long start = System.currentTimeMillis();
        Map<String, CompletableFuture<TaskOutcome<T>>> futures = new HashMap<>();

        // ① 提交所有任务
        for (ParallelTask<T> task : tasks) {
            futures.put(task.name(), submitTask(task));
        }

        // ② 整体超时控制——用 orTimeout
        CompletableFuture<Void> all = CompletableFuture.allOf(
                futures.values().toArray(new CompletableFuture[0]));

        try {
            all.orTimeout(totalTimeoutMs, TimeUnit.MILLISECONDS).join();
        } catch (Exception e) {
            log.warn("[ParallelExecutor] 整体超时或异常: {}", e.getMessage());
            // 超时不抛——继续收集已完成的结果
        }

        // ③ 聚合结果
        Map<String, T> results = new HashMap<>();
        Map<String, Boolean> successFlags = new HashMap<>();
        Map<String, String> errors = new HashMap<>();
        boolean allCriticalOk = true;

        for (Map.Entry<String, CompletableFuture<TaskOutcome<T>>> entry : futures.entrySet()) {
            String name = entry.getKey();
            CompletableFuture<TaskOutcome<T>> f = entry.getValue();
            ParallelTask<T> task = findTask(tasks, name);

            if (f.isDone() && !f.isCompletedExceptionally()) {
                TaskOutcome<T> outcome = f.getNow(null);
                if (outcome != null && outcome.success) {
                    results.put(name, outcome.value);
                    successFlags.put(name, true);
                } else {
                    handleFailure(task, outcome == null ? "empty outcome" : outcome.error,
                            results, successFlags, errors);
                    if (task.critical()) allCriticalOk = false;
                }
            } else {
                // 未完成（超时）或被取消
                handleFailure(task, "timeout or cancelled",
                        results, successFlags, errors);
                if (task.critical()) allCriticalOk = false;
            }
        }

        long total = System.currentTimeMillis() - start;
        log.info("[ParallelExecutor] 完成: tasks={}, success={}, costMs={}",
                tasks.size(),
                successFlags.values().stream().filter(b -> b).count(),
                total);

        return new ParallelResult<>(results, successFlags, errors, total, allCriticalOk);
    }

    /**
     * 提交单个任务——带单任务超时 + 重试
     * <p>
     * <b>D61 改动</b>：任务执行逻辑包一层 RetryTemplate。
     * <p>
     * <b>顺序关系</b>：
     * <ol>
     *   <li>重试在任务内部循环——最多 N 次</li>
     *   <li>超时作用于整个任务（含所有重试）——避免重试导致总耗时爆炸</li>
     * </ol>
     */
    private <T> CompletableFuture<TaskOutcome<T>> submitTask(ParallelTask<T> task) {
        long timeout = task.timeoutMs() > 0
                ? task.timeoutMs()
                : props.getDefaultTaskTimeoutMs();

        CompletableFuture<TaskOutcome<T>> future = CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                // ★ D61 核心：用 RetryTemplate 包一层
                T value = workflowRetryTemplate.execute(
                        task.name(),
                        task.action(),
                        RetryPolicy.EXPONENTIAL_JITTER,
                        RetryPredicates.defaultPredicate()
                );
                long cost = System.currentTimeMillis() - start;
                if (props.isRecordTaskTiming()) {
                    log.debug("[ParallelTask] {} ✅ costMs={}", task.name(), cost);
                }
                return TaskOutcome.<T>success(value);

            } catch (RetryExhaustedException e) {
                // 重试耗尽——作为任务失败处理
                long cost = System.currentTimeMillis() - start;
                log.warn("[ParallelTask] {} ❌ 重试耗尽 costMs={} err={}",
                        task.name(), cost, e.getStats().finalError());
                return TaskOutcome.<T>failure(e.getStats().finalError());

            } catch (Exception e) {
                long cost = System.currentTimeMillis() - start;
                log.warn("[ParallelTask] {} ❌ costMs={} err={}",
                        task.name(), cost, e.getMessage());
                return TaskOutcome.<T>failure(e.getMessage());
            }
        }, executor);

        return future.orTimeout(timeout, TimeUnit.MILLISECONDS);
    }

    /** 处理失败任务——关键任务抛异常，非关键用 fallback */
    private <T> void handleFailure(ParallelTask<T> task,
                                   String error,
                                   Map<String, T> results,
                                   Map<String, Boolean> successFlags,
                                   Map<String, String> errors) {
        successFlags.put(task.name(), false);
        errors.put(task.name(), error);

        if (task.fallback() != null) {
            results.put(task.name(), task.fallback());
            log.info("[ParallelTask] {} 使用兜底值", task.name());
        } else if (!task.critical()) {
            log.warn("[ParallelTask] {} 失败且无兜底——结果为空", task.name());
        }
    }

    /**
     * 串行退化执行（关闭并行开关时）
     * <p>
     * <b>D61 改动</b>：每个任务仍然走 RetryTemplate——
     * 并行/串行是"调度策略"，重试是"容错策略"，两者独立。
     * <p>
     * <b>执行流程</b>：
     * <pre>
     *   for 每个任务:
     *       try:
     *           结果 = retryTemplate.execute(任务名, 任务逻辑)
     *           记录成功
     *       catch RetryExhaustedException:
     *           如果是关键任务 → 标记失败
     *           否则 → 使用兜底值
     * </pre>
     */
    private <T> ParallelResult<T> executeSequentially(List<ParallelTask<T>> tasks) {
        long start = System.currentTimeMillis();
        Map<String, T> results = new HashMap<>();
        Map<String, Boolean> flags = new HashMap<>();
        Map<String, String> errors = new HashMap<>();
        boolean allOk = true;

        for (ParallelTask<T> task : tasks) {
            try {
                // ★ D61 核心：每个任务走重试
                T v = workflowRetryTemplate.execute(
                        task.name(),
                        task.action(),
                        RetryPolicy.EXPONENTIAL_JITTER,
                        RetryPredicates.defaultPredicate()
                );
                results.put(task.name(), v);
                flags.put(task.name(), true);

            } catch (RetryExhaustedException e) {
                // 重试耗尽——按关键性分别处理
                String error = e.getStats().finalError();
                flags.put(task.name(), false);
                errors.put(task.name(), error);

                if (task.fallback() != null) {
                    results.put(task.name(), task.fallback());
                    log.info("[SequentialTask] {} 使用兜底值", task.name());
                } else if (task.critical()) {
                    allOk = false;
                    log.error("[SequentialTask] {} 关键任务失败——整体失败: {}",
                            task.name(), error);
                } else {
                    log.warn("[SequentialTask] {} 失败且无兜底——结果为空", task.name());
                }

            } catch (Exception e) {
                // 兜底——非 RetryExhaustedException 的异常
                String error = e.getMessage();
                flags.put(task.name(), false);
                errors.put(task.name(), error);
                if (task.fallback() != null) {
                    results.put(task.name(), task.fallback());
                } else if (task.critical()) {
                    allOk = false;
                }
            }
        }

        return new ParallelResult<>(results, flags, errors,
                System.currentTimeMillis() - start, allOk);
    }

    private <T> ParallelTask<T> findTask(List<ParallelTask<T>> tasks, String name) {
        return tasks.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        log.info("[ParallelExecutor] 已关闭");
    }

    /** 内部：任务执行结果 */
    private static class TaskOutcome<T> {
        final boolean success;
        final T value;
        final String error;

        private TaskOutcome(boolean success, T value, String error) {
            this.success = success;
            this.value = value;
            this.error = error;
        }

        static <T> TaskOutcome<T> success(T value) {
            return new TaskOutcome<>(true, value, null);
        }

        static <T> TaskOutcome<T> failure(String error) {
            return new TaskOutcome<>(false, null, error);
        }
    }
}