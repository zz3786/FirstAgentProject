package org.example.rag.ingest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * D55 异步索引配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.async-index")
public class AsyncIndexProperties {

    /** 总开关——关掉退化为同步 */
    private boolean enabled = true;

    /**
     * 并发处理的文件数
     * <p>
     * <b>怎么选</b>：
     * - 太小（1-2）—— 没有并发收益
     * - 太大（>8）—— embedding API 限流、MySQL 连接池打满
     * - 建议：CPU 核数 或 4-6
     */
    private int concurrency = 4;

    /**
     * 任务队列容量
     * <p>
     * <b>作用</b>：背压——队列满时生产者阻塞——防止内存暴涨。
     * <p>
     * <b>怎么选</b>：100-1000 够用。
     */
    private int queueCapacity = 500;

    /**
     * 单文件处理超时（秒）
     * <p>
     * 超过则放弃该文件——记录失败——不影响其他。
     */
    private int perFileTimeoutSeconds = 300;

    /**
     * 每个文件之间的最小间隔（毫秒）
     * <p>
     * <b>为什么保留</b>：embedding API 有 QPS 限流——全速跑会被 429。
     * 0 = 不限速。
     */
    private long minIntervalMs = 200;

    /**
     * 进度日志间隔——每处理 N 个文件打一次日志
     */
    private int progressLogInterval = 10;
}