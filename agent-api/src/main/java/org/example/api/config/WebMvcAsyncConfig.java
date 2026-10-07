package org.example.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * WebMVC 异步请求线程池配置
 *
 * <h3>解决的问题</h3>
 * <p>
 * {@code ChatController.streamR()} 返回 {@code Flux<String>}，
 * Spring MVC 通过 SSE 协议异步推送响应。异步请求需要一个
 * {@link org.springframework.core.task.AsyncTaskExecutor} 来执行。
 * 未配置时，Spring MVC fallback 到
 * {@link org.springframework.core.task.SimpleAsyncTaskExecutor}——
 * <b>每次请求新建一个线程，不池化、不限流</b>。
 * 高并发时线程数爆炸，JVM 会 OOM，并打印 warning：
 * <pre>
 * Performing asynchronous handling through the default Spring MVC SimpleAsyncTaskExecutor.
 * This executor is not suitable for production use under load.
 * </pre>
 *
 * <h3>为什么用 WebMvcConfigurer 而不是 yml 配置</h3>
 * <p>
 * {@code spring.task.execution.pool.*} 影响的是 {@code @Async}
 * 的默认执行器，<b>不直接作用于 Spring MVC 的异步请求处理</b>。
 * 要让 Spring MVC 使用自定义线程池，必须通过
 * {@link WebMvcConfigurer#configureAsyncSupport} 注册。
 *
 * <h3>参数选择依据</h3>
 * <p>
 * SSE 请求的特点是"占用时间长（几秒到几十秒）、消耗 CPU 少"——
 * 大部分时间在等 LLM 返回、等 RAG 检索。所以：
 * <ul>
 *   <li>{@code corePoolSize} 给足——避免高峰期频繁扩缩</li>
 *   <li>{@code maxPoolSize} 更大——支持突发并发</li>
 *   <li>拒绝策略用 {@code CallerRunsPolicy}——队列满时"减速"而非"拒绝"</li>
 * </ul>
 *
 * <h3>线程数怎么估</h3>
 * <p>
 * 用 Little's Law：{@code maxPoolSize ≈ 峰值 QPS × 平均处理时长（秒）}。
 * <p>
 * 假设峰值 20 QPS，每次 SSE 请求平均占用线程 15 秒 →
 * 需要线程数 ≈ 20 × 15 = 300。当前配置（max=100）适合中小规模，
 * 若发现 {@code queueSize} 长期占用 > 80%，应先调大 maxPoolSize。
 */
@Slf4j
@Configuration
public class WebMvcAsyncConfig implements WebMvcConfigurer {

    /**
     * SSE 专用线程池
     * <p>
     * <b>Bean 名不能叫 {@code taskExecutor}</b>——那个名字被
     * Spring Boot 的自动配置占用，会冲突。这里命名为
     * {@code sseTaskExecutor}，只被本类内部和 Spring MVC 异步
     * 支持体系使用，不会影响其他 {@code @Async} 场景。
     */
    @Bean("sseTaskExecutor")
    public ThreadPoolTaskExecutor sseTaskExecutor() {
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
        configurer.setTaskExecutor(sseTaskExecutor());

        // ② SSE 请求超时——5 分钟
        //    必须大于最长 LLM 响应时间。你项目的完整链路
        //    （前置检索 + 两轮 LLM + 推荐块）可能到 30 秒以上，
        //    默认的 30 秒会被 Spring MVC 提前中断。
        configurer.setDefaultTimeout(300_000L);
    }
}