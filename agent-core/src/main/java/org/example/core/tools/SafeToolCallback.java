package org.example.core.tools;

import lombok.extern.slf4j.Slf4j;
import org.example.common.utils.TextUtils;
import org.example.core.retry.RetryExhaustedException;
import org.example.core.retry.RetryPredicates;
import org.example.core.retry.AiRetryTemplate;
import org.example.core.retry.model.RetryPolicy;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.*;

/**
 * 工具回调安全包装
 *
 * <h3>四层保护</h3>
 * <ol>
 *   <li><b>重试</b>——网络抖动/5xx/超时时用 WorkflowRetryTemplate 自动重发</li>
 *   <li><b>超时控制</b>——Future.get(timeout)——防工具卡死</li>
 *   <li><b>日志</b>——入参出参打点，便于排查</li>
 *   <li><b>异常兜底</b>——失败返回友好文本，不抛原始异常给 LLM</li>
 * </ol>
 *
 * <h3>为什么加"工具层重试"</h3>
 * 工具失败分两类：
 * <ul>
 *   <li><b>临时故障</b>（网络/5xx/超时）——该由代码机械重试，不该麻烦 LLM</li>
 *   <li><b>逻辑错误</b>（参数/权限）——该让 LLM 决策，代码重试无意义</li>
 * </ul>
 * RetryPredicates.defaultPredicate() 会排除参数类异常——保证只重试"临时故障"。
 *
 * <h3>超时设置</h3>
 * TIMEOUT_SECONDS = 5 —— 单次 5 秒。
 * 结合重试最多 3 次 + 退避——总耗时上限约 15.6 秒——在调用方（StepExecutor 30s）容忍范围内。
 */
@Slf4j
public class SafeToolCallback implements ToolCallback {

    /** 单次执行的超时（秒） */
    private static final long TIMEOUT_SECONDS = 5;

    /** 入参日志最大打印长度——防敏感/超长内容刷屏 */
    private static final int MAX_LOG_INPUT_CHARS = 200;

    /** 出参日志最大打印长度 */
    private static final int MAX_LOG_OUTPUT_CHARS = 5000;

    /** 工具执行的线程池——用于超时控制 */
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            16, 32,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r, "tool-callback-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final ToolCallback delegate;

    /** 重试模板——工具失败时自动重发 */
    private final AiRetryTemplate retryTemplate;

    public SafeToolCallback(ToolCallback delegate, AiRetryTemplate retryTemplate) {
        this.delegate = delegate;
        this.retryTemplate = retryTemplate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    // ==================== 无 ToolContext ====================

    @Override
    public String call(String toolInput) {
        return executeSafely(toolInput, () -> delegate.call(toolInput));
    }

    // ==================== 带 ToolContext ====================

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return executeSafely(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    // ==================== 统一执行骨架 ====================

    /**
     * 执行骨架：重试 + 超时 + 日志 + 兜底
     * <p>
     * 关键：用 RetryTemplate 包整个"提交 + 等待"流程——
     * 网络抖动时自动重发，不把临时故障暴露给 LLM。
     */
    private String executeSafely(String toolInput, Callable<String> action) {
        String name = delegate.getToolDefinition().name();
        long start = System.currentTimeMillis();

        log.info("🔧 [工具调用开始] {} | 入参: {}",name, TextUtils.truncate(toolInput, MAX_LOG_INPUT_CHARS));

        try {
            // ★ RetryTemplate 包住整个执行流程
            String result = retryTemplate.execute(
                    "tool:" + name,
                    () -> {
                        try {
                            return runWithTimeout(action, name);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    },
                    RetryPolicy.EXPONENTIAL_JITTER,
                    RetryPredicates.defaultPredicate()
            );

            long cost = System.currentTimeMillis() - start;
            log.info("✅ [工具调用成功] {} | 耗时: {}ms | 出参: {}",
                    name, cost,
                    TextUtils.truncate(result, MAX_LOG_OUTPUT_CHARS));
            return result;

        } catch (RetryExhaustedException e) {
            long cost = System.currentTimeMillis() - start;
            log.error("❌ [工具调用重试耗尽] {} | 耗时: {}ms | attempts={} | 原因: {}",
                    name, cost, e.getStats().totalAttempts(), e.getStats().finalError());
            return "⚠️ 工具 " + name + " 多次尝试后仍失败："
                    + e.getStats().finalError() + "。请稍后重试或换一种方式提问。";

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("❌ [工具调用未知异常] {} | 耗时: {}ms", name, cost, e);
            return "⚠️ 工具 " + name + " 出现未知错误，请稍后重试。";
        }
    }

    /**
     * 单次执行——带超时控制
     * <p>
     * 抽出这个方法的原因：让 RetryTemplate 重试的是"提交+等待"整个流程，
     * 而不仅仅是一次 submit。
     */
    private String runWithTimeout(Callable<String> action, String name) throws Exception {
        Future<String> future = EXECUTOR.submit(action);
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("工具 " + name + " 执行超时（>" + TIMEOUT_SECONDS + "秒）");
        }
    }
}