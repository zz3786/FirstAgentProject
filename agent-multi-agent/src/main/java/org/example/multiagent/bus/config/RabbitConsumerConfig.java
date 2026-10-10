package org.example.multiagent.bus.config;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.bus.AgentMessageConsumer;
import org.example.multiagent.bus.ResultCorrelationListener;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * RabbitMQ 消费者容器配置（D74）
 *
 * <h3>为什么只有一个 List Bean</h3>
 * <p>
 * 首版用过两个 Bean：{@code taskListenerContainers} + {@code resultListenerContainers}。
 * 但 {@code ContainerLifecycleManager} 的构造函数需要注入
 * {@code List<SimpleMessageListenerContainer>}——两个同类型 Bean 会触发
 * {@code NoUniqueBeanDefinitionException}。
 * <p>
 * 合并成一个 Bean 解决问题——同时构造 task 和 result 两种容器，
 * 合并到一个 List 返回。语义更清晰、装配无歧义。
 *
 * <h3>为什么不用 @Qualifier</h3>
 * <p>
 * {@code ContainerLifecycleManager} 需要<b>全部</b>容器都能启动——
 * 注入一个 {@code @Qualifier} 只能拿一半。需要写两个字段分别注入再合并——
 * 更复杂、更易错。合并 Bean 是更简洁的解法。
 *
 * <h3>为什么不直接给每个容器单独 @Bean</h3>
 * <p>
 * 队列数量由 yml 动态决定（financial / technical / legal + 未来可能加），
 * 无法用固定数量的 {@code @Bean} 方法声明。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
        name = "app.agent-bus.rabbit.enabled",
        havingValue = "true"
)
public class RabbitConsumerConfig {

    private final RabbitBusProperties props;

    public RabbitConsumerConfig(RabbitBusProperties props) {
        this.props = props;
    }

    /**
     * 所有角色的 Task + Result 队列消费者容器（合并版）
     *
     * <h3>容器清单</h3>
     * <pre>
     *   每个角色的 task queue  → 一个容器 → AgentMessageConsumer.handleTask
     *   每个角色的 result queue → 一个容器 → ResultCorrelationListener.handleResult
     * </pre>
     *
     * <h3>返回 List 而非逐个声明</h3>
     * <p>
     * Spring 会自动收集 {@code List<T>} 类型的 Bean——但需要
     * 实现者显式启动它们（Spring 不会自动 start List 里的元素）。
     * 启动由 {@code ContainerLifecycleManager} 负责。
     */
    @Bean
    public List<SimpleMessageListenerContainer> allListenerContainers(
            ConnectionFactory connectionFactory,
            AgentMessageConsumer taskConsumer,
            ResultCorrelationListener resultListener) {

        List<SimpleMessageListenerContainer> containers = new ArrayList<>();

        props.getQueues().forEach((roleName, qc) -> {
            // ── Task 容器
            String taskQueue = qc.getTaskQueue();
            if (taskQueue != null && !taskQueue.isBlank()) {
                containers.add(createContainer(
                        connectionFactory,
                        taskQueue,
                        "task:" + roleName,
                        message -> taskConsumer.handleTask(roleName, message)));
            }

            // ── Result 容器
            String resultQueue = qc.getResultQueue();
            if (resultQueue != null && !resultQueue.isBlank()) {
                containers.add(createContainer(
                        connectionFactory,
                        resultQueue,
                        "result:" + roleName,
                        message -> resultListener.handleResult(roleName, message)));
            }
        });

        log.info("[RabbitConsumer] 共注册 {} 个消费者容器", containers.size());
        return containers;
    }

    // ==================== 内部 ====================

    /**
     * 创建一个消费者容器
     *
     * @param connectionFactory RabbitMQ 连接工厂
     * @param queueName         监听的队列名
     * @param label             标签（用于日志）——如 "task:financial" / "result:legal"
     * @param listener          消息监听器
     */
    private SimpleMessageListenerContainer createContainer(
            ConnectionFactory connectionFactory,
            String queueName,
            String label,
            MessageListener listener) {

        SimpleMessageListenerContainer container =
                new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueName);
        container.setConcurrentConsumers(props.getConsumer().getConcurrency());
        container.setPrefetchCount(props.getConsumer().getPrefetch());
        // 处理异常时不 requeue——走 DLX
        container.setDefaultRequeueRejected(false);
        container.setMessageListener(listener);
        container.setAutoStartup(true);

        log.info("[RabbitConsumer] 注册消费者: label={}, queue={}, concurrency={}, prefetch={}",
                label, queueName,
                props.getConsumer().getConcurrency(),
                props.getConsumer().getPrefetch());

        return container;
    }
}