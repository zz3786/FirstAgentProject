package org.example.api.config;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.api.interceptor.SecurityInterceptor;
import org.example.observability.tracing.TraceContextTaskDecorator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * WebMVC 异步请求线程池配置（D32 建，D77 加 TraceContext 传播）
 *
 * <h3>D77 更新</h3>
 * <p>
 * 给线程池加 {@link TraceContextTaskDecorator}——保证 SSE 异步请求的
 * Span 能延续父请求的 TraceContext，不会在 Zipkin 里"断链"。
 *
 * <h3>为什么把 sseTaskExecutor 改成 static Bean 方法</h3>
 * <p>
 * D32 首版非 static——直接从本类方法调用。但加 TaskDecorator 后，
 * Bean 方法需要参数（{@code ObjectProvider<TraceContextTaskDecorator>}），
 * 不能直接无参调用。
 * <p>
 * 改为"static Bean 方法 + 构造器注入"的经典模式：
 * <ul>
 *   <li>static Bean 方法——创建 Bean（Spring 允许，且推荐）</li>
 *   <li>构造器注入——本类实例持有 Bean 引用</li>
 *   <li>避免"Bean 方法互相调用"导致的代理问题</li>
 * </ul>
 */
@Slf4j
@Configuration
public class WebMvcAsyncConfig implements WebMvcConfigurer {

    @Resource
    private SecurityInterceptor securityInterceptor;

    private final ThreadPoolTaskExecutor sseTaskExecutor;

    public WebMvcAsyncConfig(
            @Qualifier("sseTaskExecutor") ThreadPoolTaskExecutor sseTaskExecutor) {
        this.sseTaskExecutor = sseTaskExecutor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(securityInterceptor)
                .addPathPatterns("/**") // 拦截所有请求
                .excludePathPatterns(
                        "/auth/login",
                        "/actuator/**",
                        "/swagger-ui/**",
                        "/v3/api-docs/**"
                ); // 排除登录、监控、文档页面
    }

    @Bean("sseTaskExecutor")
    public static ThreadPoolTaskExecutor sseTaskExecutor(
            ObjectProvider<TraceContextTaskDecorator> taskDecoratorProvider) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // ① 核心线程数——常驻线程，避免请求高峰频繁扩缩
        executor.setCorePoolSize(20);

        // ② 最大线程数——支持突发并发
        executor.setMaxPoolSize(100);

        // ③ 队列容量——核心线程满后先入队，队列满才扩到 max
        executor.setQueueCapacity(200);

        // ④ 非核心线程空闲存活时间
        executor.setKeepAliveSeconds(60);

        // ⑤ 线程名前缀——便于 jstack / 监控定位
        executor.setThreadNamePrefix("sse-async-");

        // ⑥ 拒绝策略——队列满时由调用线程（Tomcat 工作线程）自己执行。
        //    效果是"降低接收速度"（背压），而不是"拒绝请求"（503）。
        //    对 SSE 场景更友好：用户宁愿慢一点，也不想看到 503。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // ★ D77：TraceContext 传播——SSE 异步请求的 Span 才能续上父请求
        TraceContextTaskDecorator taskDecorator = taskDecoratorProvider.getIfAvailable();
        if (taskDecorator != null) {
            executor.setTaskDecorator(taskDecorator);
            log.info("[WebMvcAsyncConfig] 已启用 TraceContext 传播");
        } else {
            log.info("[WebMvcAsyncConfig] 未启用 TraceContext 传播（Tracer 缺失）");
        }

        // ⑦ 优雅关闭——应用停止时等正在进行的 SSE 请求完成
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();

        log.info("[WebMvcAsyncConfig] SSE 线程池初始化: core={}, max={}, queue={}, keepAlive={}s",
                executor.getCorePoolSize(),
                executor.getMaxPoolSize(),
                executor.getQueueCapacity(),
                executor.getKeepAliveSeconds());

        return executor;
    }

    /**
     * 把 {@code sseTaskExecutor} 注册到 Spring MVC 的异步支持体系。
     *
     * <p>此方法覆盖 Spring MVC 默认的 {@code SimpleAsyncTaskExecutor}，
     * 消除启动 warning。
     *
     * <p><b>为什么直接调 {@code sseTaskExecutor()}</b>：
     * 本类被 {@code @Configuration} 注解，Spring 会用 CGLIB 增强，
     * 方法调用会被拦截并返回容器中的单例 Bean——不会创建第二个线程池。
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // ① 使用专用线程池替代 SimpleAsyncTaskExecutor
        configurer.setTaskExecutor(sseTaskExecutor);
        // ② SSE 请求超时——5 分钟
        //    必须大于最长 LLM 响应时间。你项目的完整链路
        //    （前置检索 + 两轮 LLM + 推荐块）可能到 30 秒以上，
        //    默认的 30 秒会被 Spring MVC 提前中断。
        configurer.setDefaultTimeout(300_000L);
    }
}