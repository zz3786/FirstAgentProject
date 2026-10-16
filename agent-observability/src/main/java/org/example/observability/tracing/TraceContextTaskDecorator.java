package org.example.observability.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskDecorator;

/**
 * 线程池的 TraceContext 传播装饰器（D77）
 *
 * <h3>它解决什么问题</h3>
 * <p>
 * Micrometer 的当前 Span 存在 {@code ThreadLocal} 里——跨线程时
 * 子线程看不到父线程的 Span。结果：
 * <ul>
 *   <li>SSE 异步处理、异步索引、Plan StepExecutor 的 Span 都断链</li>
 *   <li>Zipkin 显示"两个独立的 trace"而不是父子关系</li>
 * </ul>
 *
 * <h3>原理</h3>
 * <p>
 * {@code TaskDecorator} 是 Spring 线程池的钩子——在任务提交时
 * 被调用，返回包装后的 Runnable。我们在包装时捕获"当前 Span"，
 * 在子线程执行时重新设置。
 *
 * <h3>为什么捕获 Span 而不是 TraceContext</h3>
 * <p>
 * {@code Span} 已经持有 {@code TraceContext} 的引用——
 * {@code Tracer.withSpan(span)} 会从 span 中恢复整个上下文。
 * 捕获 Span 更直接。
 *
 * <h3>为什么用 try-with-resources</h3>
 * <p>
 * {@code Tracer.SpanInScope} 是 AutoCloseable——
 * close 时会恢复"进入前的 Span"。避免子线程执行完后
 * ThreadLocal 残留（虽然线程池会重用，但显式清理更安全）。
 */
@Slf4j
public class TraceContextTaskDecorator implements TaskDecorator {

    private final Tracer tracer;

    public TraceContextTaskDecorator(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        // ① 捕获提交时的当前 Span
        Span currentSpan = tracer.currentSpan();

        // ② 返回包装后的 Runnable
        return () -> {
            if (currentSpan == null) {
                // 无父 Span——正常执行
                runnable.run();
                return;
            }
            try (Tracer.SpanInScope ws = tracer.withSpan(currentSpan)) {
                runnable.run();
            } catch (Throwable t) {
                // 不影响业务——异常继续抛
                throw t;
            }
        };
    }
}