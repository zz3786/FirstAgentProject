package org.example.core.tools.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 工具调用专用线程池（D77 收尾——从 SafeToolCallback 静态池迁出）
 *
 * <h3>为什么要从 static final 迁出</h3>
 * <ul>
 *   <li>静态池无法被 Spring 管理生命周期——不会优雅关闭</li>
 *   <li>多 {@code @SpringBootTest} 共享同一静态池——测试残留互相污染</li>
 *   <li>参数无法通过 yml 覆盖</li>
 *   <li>多实例部署时"16 个线程"被放大 N 倍——资源浪费</li>
 * </ul>
 *
 * <h3>参数选择依据</h3>
 * <ul>
 *   <li>core=16——工具调用大部分时间是等 IO，不需要太多核心线程</li>
 *   <li>max=32——突发并发支持</li>
 *   <li>queue=200——队列满时 CallerRunsPolicy 背压——不丢请求</li>
 *   <li>超时 5s（在 SafeToolCallback 里配）——结合 3 次重试最多 15s</li>
 * </ul>
 *
 * <h3>Bean 名为什么要叫 toolCallbackExecutor</h3>
 * <p>
 * 不能叫 {@code taskExecutor}——那个名字被 Spring Boot 自动配置占用。
 */
@Slf4j
@Configuration
public class ToolExecutorConfig {

    @Bean("toolCallbackExecutor")
    public ThreadPoolTaskExecutor toolCallbackExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(16);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("tool-callback-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        log.info("[ToolExecutorConfig] 工具线程池初始化: core={}, max={}, queue={}",
                executor.getCorePoolSize(),
                executor.getMaxPoolSize(),
                executor.getQueueCapacity());
        return executor;
    }
}