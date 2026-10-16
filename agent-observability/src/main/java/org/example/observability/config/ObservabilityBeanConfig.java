package org.example.observability.config;

import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.example.observability.tracing.TraceContextTaskDecorator;
import org.example.observability.tracing.TracingAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性 Bean 装配（D77）
 *
 * <h3>为什么不用 @ConditionalOnBean(Tracer.class)</h3>
 * <p>
 * 首版用类级 {@code @ConditionalOnBean(Tracer.class)} —— 踩了 Spring
 * 的经典坑：
 * <ul>
 *   <li>Spring 处理顺序：<b>用户自定义 @Configuration → Spring Boot 自动配置</b></li>
 *   <li>{@code Tracer} 由 {@code BraveAutoConfiguration}（自动配置）创建——<b>晚于</b>本类</li>
 *   <li>评估本类条件时 {@code Tracer} 还没定义 → 条件不满足 → <b>整个类被跳过</b></li>
 * </ul>
 * 结果：{@code TracingAdvisor} / {@code TraceContextTaskDecorator} 都没注册。
 *
 * <h3>解决方案：ObjectProvider + @Bean 返回 null</h3>
 * <p>
 * Spring 允许 {@code @Bean} 方法返回 {@code null}——此时该 Bean 不注册，
 * 且<b>不会导致启动失败</b>。用 {@code ObjectProvider<Tracer>} 探测：
 * <ul>
 *   <li>有 Tracer → 创建并返回正常 Bean</li>
 *   <li>无 Tracer → 记录警告，返回 null（跳过）</li>
 * </ul>
 * <p>
 * 这比 {@code @ConditionalOnBean} 更可靠——不依赖 Bean 加载顺序。
 *
 * <h3>为什么还需要 @ConditionalOnProperty</h3>
 * <p>
 * 类级 {@code @ConditionalOnProperty} 是纯"配置开关"——
 * 不依赖其他 Bean，评估时机无问题。
 * 用于"用户想临时关闭 tracing 埋点"的场景。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
        name = "app.observability.tracing.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ObservabilityBeanConfig {

    /**
     * ChatClient 追踪 Advisor
     * <p>
     * 使用方（{@code ChatClientConfig}）需要把它加进
     * {@code ChatClient.defaultAdvisors(...)}。
     */
    @Bean
    public TracingAdvisor tracingAdvisor(
            ObjectProvider<Tracer> tracerProvider,
            TracingProperties props) {

        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            log.warn("[Observability] Tracer 未就绪——TracingAdvisor 不注册。" +
                    "检查 micrometer-tracing-bridge-brave 依赖");
            return null;
        }

        log.info("[Observability] 装配 TracingAdvisor: enabled={}, traceChatClient={}, traceToolCalls={}",
                props.isEnabled(), props.isTraceChatClient(), props.isTraceToolCalls());
        return new TracingAdvisor(tracer, props);
    }

    /**
     * 线程池的 TraceContext 传播装饰器
     * <p>
     * 使用方（{@code WebMvcAsyncConfig}）用
     * {@code executor.setTaskDecorator(...)} 应用。
     */
    @Bean
    public TraceContextTaskDecorator traceContextTaskDecorator(
            ObjectProvider<Tracer> tracerProvider) {

        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            log.warn("[Observability] Tracer 未就绪——TraceContextTaskDecorator 不注册。" +
                    "检查 micrometer-tracing-bridge-brave 依赖");
            return null;
        }

        log.info("[Observability] 装配 TraceContextTaskDecorator");
        return new TraceContextTaskDecorator(tracer);
    }
}