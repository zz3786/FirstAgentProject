package org.example.core.workflow.core.parallel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 并行执行配置
 * <p>
 * yml 前缀：app.parallel.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.parallel")
public class ParallelProperties {

    /** 总开关——关掉则所有并行任务退化为串行执行 */
    private boolean enabled = true;

    /**
     * 是否使用虚拟线程
     * <p>
     * true：Java 21 虚拟线程——IO 密集场景推荐，线程数不受限
     * false：使用平台线程池——CPU 密集或需要精细控制时用
     */
    private boolean useVirtualThreads = true;

    /**
     * 平台线程池大小（useVirtualThreads=false 时生效）
     */
    private int platformPoolSize = 16;

    /** 平台线程池队列容量 */
    private int platformQueueCapacity = 256;

    /** 默认并行超时（毫秒）——单次 executeAll 的整体超时 */
    private long defaultTimeoutMs = 5000L;

    /** 单个任务默认超时（毫秒）——不配置时用这个 */
    private long defaultTaskTimeoutMs = 3000L;

    /**
     * 是否记录每个任务的耗时
     * <p>
     * 生产环境建议 true——可观测性
     */
    private boolean recordTaskTiming = true;
}