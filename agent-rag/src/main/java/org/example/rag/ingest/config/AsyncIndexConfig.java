package org.example.rag.ingest.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * D55 异步索引线程池
 * <p>
 * <b>关键配置</b>：
 * <ul>
 *   <li>corePoolSize = maxPoolSize = concurrency——固定线程数</li>
 *   <li>queueCapacity——有界队列——背压</li>
 *   <li>CallerRunsPolicy——队列满时由调用线程自己执行——不丢任务</li>
 * </ul>
 */
@Slf4j
@Configuration
public class AsyncIndexConfig {

    @Bean("asyncIndexExecutor")
    public ThreadPoolTaskExecutor asyncIndexExecutor(AsyncIndexProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int concurrency = Math.max(1, props.getConcurrency());

        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(props.getQueueCapacity());
        executor.setThreadNamePrefix("async-index-");

        // ★ 队列满时——调用者自己执行——阻塞生产者——不丢任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // ★ 优雅关闭——等任务完成
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        log.info("[D55] 异步索引线程池初始化: concurrency={}, queueCapacity={}",
                concurrency, props.getQueueCapacity());

        return executor;
    }
}