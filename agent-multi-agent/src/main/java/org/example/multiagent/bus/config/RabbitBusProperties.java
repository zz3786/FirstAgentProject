package org.example.multiagent.bus.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RabbitMQ 总线配置（D74）
 *
 * <h3>yml 结构</h3>
 * <pre>
 * app:
 *   agent-bus:
 *     rabbit:
 *       enabled: true
 *       exchange: { name, type, durable, auto-delete }
 *       dead-letter: { exchange, queue, routing-key }
 *       queues:
 *         financial: { task-queue, result-queue }
 *         technical: { task-queue, result-queue }
 *         legal:     { task-queue, result-queue }
 *       consumer: { concurrency, prefetch, max-retries, retry-backoff-ms }
 *       idempotency: { ttl-seconds, key-prefix }
 * </pre>
 *
 * <h3>为什么 queues 用 Map&lt;String, QueueConfig&gt;</h3>
 * <p>
 * key 是角色名（yml 里写的），value 是队列名。加新角色只改 yml——
 * 队列声明和消费者注册都是遍历这个 Map 动态生成。
 * <p>
 * 而 {@code consumers} 段（D73 的 worker 配置）用的是另一个 Map——
 * 两者 key 一致（financial / technical / legal），语义相关但职责不同：
 * <ul>
 *   <li>{@code app.workers.*}——Worker 的"业务配置"（角色、Prompt、工具集）</li>
 *   <li>{@code app.agent-bus.rabbit.queues.*}——Worker 的"通信配置"（队列名）</li>
 * </ul>
 * 分开的好处：通信配置属于"基础设施"，业务配置属于"业务"——
 * 未来换 MQ 类型（RabbitMQ → Kafka）时只改通信配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent-bus.rabbit")
public class RabbitBusProperties {

    /** 总开关——false 时 Bus Bean 不注册 */
    private boolean enabled = false;

    /** 主 Exchange 配置 */
    private Exchange exchange = new Exchange();

    /** 死信配置 */
    private DeadLetter deadLetter = new DeadLetter();

    /** 每个角色的队列配置——key 是角色名 */
    private Map<String, QueueConfig> queues = new LinkedHashMap<>();

    /** 消费者参数 */
    private Consumer consumer = new Consumer();

    /** 幂等去重参数 */
    private Idempotency idempotency = new Idempotency();

    // ==================== 嵌套配置 ====================

    @Data
    public static class Exchange {
        private String name = "fap.agent.topic";
        private String type = "topic";
        private boolean durable = true;
        private boolean autoDelete = false;
    }

    @Data
    public static class DeadLetter {
        private String exchange = "fap.agent.dlx";
        private String queue = "fap.agent.dlq";
        private String routingKey = "dead-letter";
    }

    @Data
    public static class QueueConfig {
        private String taskQueue;
        private String resultQueue;
    }

    @Data
    public static class Consumer {
        /** 每个队列的并发消费者数 */
        private int concurrency = 2;
        /** 一次预取消息数 */
        private int prefetch = 5;
        /** 消费失败重试次数——达到后进 DLQ */
        private int maxRetries = 3;
        /** 重试基础退避（毫秒） */
        private long retryBackoffMs = 1000L;
    }

    @Data
    public static class Idempotency {
        /** 幂等 key TTL（秒）——防重复消费 */
        private long ttlSeconds = 600L;
        /** Redis key 前缀 */
        private String keyPrefix = "AGENT_MSG_IDEMPOTENT:";
    }

    // ==================== 便捷方法 ====================

    /**
     * Task 消息的 routing key
     */
    public String taskRoutingKey(String roleName) {
        return "agent." + roleName.toLowerCase() + ".task";
    }

    /**
     * Result 消息的 routing key
     */
    public String resultRoutingKey(String roleName) {
        return "agent." + roleName.toLowerCase() + ".result";
    }
}