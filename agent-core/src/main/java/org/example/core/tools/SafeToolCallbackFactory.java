package org.example.core.tools;

import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.example.core.retry.AiRetryTemplate;
import org.example.observability.config.TracingProperties;
import org.example.observability.tracing.TracedToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;

/**
 * SafeToolCallback 工厂（D66 建，D77 加 Tracing，D77 收尾改 Spring 线程池）
 *
 * <h3>线程池来源</h3>
 * <p>
 * 从 {@code ToolExecutorConfig} 注入的 Spring Bean——
 * 不再用静态池。好处：
 * <ul>
 *   <li>Spring 管理生命周期——优雅关闭</li>
 *   <li>多测试用例不共享</li>
 *   <li>参数可通过 yml 覆盖（未来）</li>
 * </ul>
 */
@Slf4j
@Component
public class SafeToolCallbackFactory {

    private final AiRetryTemplate retryTemplate;
    private final ObjectProvider<Tracer> tracerProvider;
    private final ObjectProvider<TracingProperties> tracingPropsProvider;
    private final ExecutorService toolExecutor;   // ★ 从 ThreadPoolTaskExecutor 提取

    public SafeToolCallbackFactory(
            AiRetryTemplate retryTemplate,
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<TracingProperties> tracingPropsProvider,
            @Qualifier("toolCallbackExecutor") ThreadPoolTaskExecutor toolCallbackExecutor) {   // ★

        this.retryTemplate = retryTemplate;
        this.tracerProvider = tracerProvider;
        this.tracingPropsProvider = tracingPropsProvider;
        // ★ ThreadPoolTaskExecutor 底层就是 ExecutorService——直接取
        this.toolExecutor = toolCallbackExecutor.getThreadPoolExecutor();

        log.info("[SafeToolCallbackFactory] 初始化完成，Tracer={}, TracingProps={}, pool={}",
                tracerProvider.getIfAvailable() != null ? "有" : "无",
                tracingPropsProvider.getIfAvailable() != null ? "有" : "无",
                toolCallbackExecutor.getCorePoolSize() + "/"
                        + toolCallbackExecutor.getMaxPoolSize() + "/"
                        + toolCallbackExecutor.getQueueCapacity());
    }

    /**
     * 批量包装
     */
    public ToolCallback[] wrap(ToolCallback[] rawCallbacks) {
        if (rawCallbacks == null || rawCallbacks.length == 0) {
            return new ToolCallback[0];
        }

        // ① Safe 包装——总是做
        ToolCallback[] safe = Arrays.stream(rawCallbacks)
                .map(rc -> (ToolCallback) new SafeToolCallback(rc, retryTemplate, toolExecutor))   // ★ 3 参
                .toArray(ToolCallback[]::new);

        // ② Tracing 包装——如果可用
        Tracer tracer = tracerProvider.getIfAvailable();
        TracingProperties tracingProps = tracingPropsProvider.getIfAvailable();

        if (tracer == null || tracingProps == null || !tracingProps.isEnabled()) {
            log.debug("[SafeToolCallbackFactory] 跳过 Tracing 包装（Tracer 或 Props 缺失）");
            return safe;
        }

        ToolCallback[] traced = Arrays.stream(safe)
                .map(sc -> (ToolCallback) new TracedToolCallback(sc, tracer, tracingProps))
                .toArray(ToolCallback[]::new);

        log.info("[SafeToolCallbackFactory] 包装 {} 个工具（Safe + Tracing）", traced.length);
        return traced;
    }

    /**
     * 单个包装
     */
    public ToolCallback wrap(ToolCallback raw) {
        ToolCallback safe = new SafeToolCallback(raw, retryTemplate, toolExecutor);   // ★ 3 参

        Tracer tracer = tracerProvider.getIfAvailable();
        TracingProperties tracingProps = tracingPropsProvider.getIfAvailable();

        if (tracer == null || tracingProps == null || !tracingProps.isEnabled()) {
            return safe;
        }
        return new TracedToolCallback(safe, tracer, tracingProps);
    }
}