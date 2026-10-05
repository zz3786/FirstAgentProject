package org.example.workflow.core.retry;

import lombok.extern.slf4j.Slf4j;
import org.example.workflow.core.retry.config.RetryProperties;
import org.example.workflow.core.retry.model.RetryPolicy;
import org.example.workflow.core.retry.model.RetryStats;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 重试模板——编程式重试的核心
 *
 * <h3>执行流程</h3>
 * <pre>
 *   ┌─────────────────────────────────────┐
 *   │ attempt = 1                          │
 *   │ 执行 action                          │
 *   │   ├─ 成功 → 返回结果                 │
 *   │   └─ 失败 → 检查 shouldRetry          │
 *   │              ├─ false → 抛异常        │
 *   │              └─ true ↓                │
 *   │ attempt++                            │
 *   │ if attempt > maxAttempts:            │
 *   │   抛 RetryExhaustedException          │
 *   │ else:                                │
 *   │   计算 backoff 时间                  │
 *   │   sleep(backoff)                     │
 *   │   回到"执行 action"                  │
 *   └─────────────────────────────────────┘
 * </pre>
 *
 * <h3>为什么不用 Spring Retry 的 @Retryable</h3>
 * <ul>
 *   <li>注解式重试对"动态配置"不友好——每个数据源的重试次数不同时难处理</li>
 *   <li>编程式重试更灵活——可以在运行时决定策略</li>
 *   <li>注解式重试的"AOP 代理"有坑（同类方法内部调用不生效）</li>
 * </ul>
 * <p>
 * <b>但仍兼容 Spring Retry</b>——在 pom 里保留依赖，简单场景用注解，
 * 复杂场景用本类的编程式 API。
 */
@Slf4j
@Component
public class WorkflowRetryTemplate {

    private final RetryProperties props;

    public WorkflowRetryTemplate(RetryProperties props) {
        this.props = props;
    }

    // ==================== 简化入口 ====================

    /**
     * 用全局默认策略执行
     */
    public <T> T execute(String name, Supplier<T> action) {
        return execute(name, action, RetryPolicy.EXPONENTIAL_JITTER,
                RetryPredicates.defaultPredicate());
    }

    /**
     * 带退避策略的重试
     */
    public <T> T execute(String name,
                         Supplier<T> action,
                         RetryPolicy policy) {
        return execute(name, action, policy, RetryPredicates.defaultPredicate());
    }

    // ==================== 完整入口 ====================

    /**
     * 完整参数的重试执行
     *
     * @param name      操作名——用于日志和配置查找
     * @param action    实际执行逻辑
     * @param policy    退避策略
     * @param shouldRetry 是否重试的判断函数
     * @return 成功的结果
     * @throws RetryExhaustedException 重试耗尽
     */
    public <T> T execute(String name,
                         Supplier<T> action,
                         RetryPolicy policy,
                         Predicate<Throwable> shouldRetry) {

        // ① 从配置读取该数据源的参数（覆盖全局默认）
        int maxAttempts = resolveMaxAttempts(name);
        long backoffMs = resolveBackoffMs(name);
        double multiplier = resolveBackoffMultiplier(name);
        long maxBackoffMs = resolveMaxBackoffMs(name);
        boolean jitter = resolveJitter(name);

        // ② 关闭开关时——只执行一次
        if (!props.isEnabled()) {
            log.debug("[Retry] 重试已关闭，单次执行: {}", name);
            try {
                return action.get();
            } catch (Exception e) {
                throw new RetryExhaustedException(
                        new RetryStats(name, 1, 0, 0, 0, e.getMessage()), e);
            }
        }

        long start = System.currentTimeMillis();
        Throwable lastError = null;
        long lastBackoff = 0;

        // ③ 尝试循环
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = action.get();

                // 成功——记录统计
                long cost = System.currentTimeMillis() - start;
                RetryStats stats = new RetryStats(name, attempt, attempt, cost,
                        lastBackoff, null);

                if (attempt == 1) {
                    log.debug("[Retry] {} 首次成功 costMs={}", name, cost);
                } else {
                    log.info("[Retry] {} 第 {} 次尝试成功 costMs={}",
                            name, attempt, cost);
                }
                return result;

            } catch (Throwable t) {
                lastError = t;

                // 检查是否应该重试
                if (!shouldRetry.test(t)) {
                    long cost = System.currentTimeMillis() - start;
                    log.warn("[Retry] {} 遇到不可重试的异常——中止: {}",
                            name, t.getMessage());
                    throw new RetryExhaustedException(
                            new RetryStats(name, attempt, 0, cost, 0, t.getMessage()), t);
                }

                // 已是最后一次尝试——退出循环
                if (attempt == maxAttempts) {
                    log.warn("[Retry] {} 达到最大尝试次数 {} ——放弃",
                            name, maxAttempts);
                    break;
                }

                // 计算退避时间
                lastBackoff = calculateBackoff(policy, backoffMs, multiplier,
                        maxBackoffMs, jitter, attempt);

                log.warn("[Retry] {} 第 {} 次失败: {} — {}ms 后重试",
                        name, attempt, t.getMessage(), lastBackoff);

                // 退避等待
                sleep(lastBackoff);
            }
        }

        // ④ 重试耗尽
        long totalCost = System.currentTimeMillis() - start;
        RetryStats stats = new RetryStats(name, maxAttempts, 0, totalCost,
                lastBackoff, lastError == null ? "unknown" : lastError.getMessage());
        log.error("[Retry] {} 重试耗尽: attempts={}, costMs={}, lastError={}",
                name, maxAttempts, totalCost,
                lastError == null ? "null" : lastError.getMessage());

        throw new RetryExhaustedException(stats, lastError);
    }

    /**
     * 带降级的重试——重试失败后返回兜底值，不抛异常

     * @param name      操作名
     * @param action    执行逻辑
     * @param fallback  兜底值（重试失败时返回）
     */
    public <T> T executeWithFallback(String name,
                                     Supplier<T> action,
                                     T fallback) {
        try {
            return execute(name, action);
        } catch (RetryExhaustedException e) {
            log.warn("[Retry] {} 使用兜底值: {}", name, fallback);
            return fallback;
        }
    }

    // ==================== 退避计算 ====================

    /**
     * 计算本次退避时间
     *
     * @param attempt 当前是第几次尝试（从 1 开始）——用于指数计算
     */
    private long calculateBackoff(RetryPolicy policy,
                                  long baseMs,
                                  double multiplier,
                                  long maxMs,
                                  boolean jitter,
                                  int attempt) {
        // 基础退避时间
        long backoff = switch (policy) {
            case IMMEDIATE -> 0L;
            case FIXED -> baseMs;
            case EXPONENTIAL -> (long) (baseMs * Math.pow(multiplier, attempt - 1));
            case EXPONENTIAL_JITTER -> (long) (baseMs * Math.pow(multiplier, attempt - 1));
        };

        // 上限保护——防止退避时间无限增长
        backoff = Math.min(backoff, maxMs);

        // 抖动——在指数退避基础上加 0~50% 随机量
        if (jitter && policy == RetryPolicy.EXPONENTIAL_JITTER && backoff > 0) {
            long jitterAmount = ThreadLocalRandom.current()
                    .nextLong(0, backoff / 2 + 1);
            backoff += jitterAmount;
        }

        return backoff;
    }

    /** 可中断的 sleep */
    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("重试等待被中断", e);
        }
    }

    // ==================== 配置解析 ====================

    private int resolveMaxAttempts(String name) {
        RetryProperties.SourceConfig c = props.getOverride().get(name);
        return (c != null && c.getMaxAttempts() != null)
                ? c.getMaxAttempts() : props.getDefaultMaxAttempts();
    }

    private long resolveBackoffMs(String name) {
        RetryProperties.SourceConfig c = props.getOverride().get(name);
        return (c != null && c.getBackoffMs() != null)
                ? c.getBackoffMs() : props.getDefaultBackoffMs();
    }

    private double resolveBackoffMultiplier(String name) {
        RetryProperties.SourceConfig c = props.getOverride().get(name);
        return (c != null && c.getBackoffMultiplier() != null)
                ? c.getBackoffMultiplier() : props.getDefaultBackoffMultiplier();
    }

    private long resolveMaxBackoffMs(String name) {
        RetryProperties.SourceConfig c = props.getOverride().get(name);
        return (c != null && c.getMaxBackoffMs() != null)
                ? c.getMaxBackoffMs() : props.getDefaultMaxBackoffMs();
    }

    private boolean resolveJitter(String name) {
        RetryProperties.SourceConfig c = props.getOverride().get(name);
        return (c != null && c.getJitter() != null)
                ? c.getJitter() : props.isDefaultJitter();
    }
}