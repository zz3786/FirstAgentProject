package org.example.multiagent.bus.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RabbitMQ 拓扑声明（D74）
 *
 * <h3>关键改动（D74 修复 2）</h3>
 * <p>
 * 首版用 {@code @Bean List<Queue>} 和 {@code @Bean List<Binding>} 声明业务队列——
 * <b>这不生效</b>。Spring AMQP 的 {@code RabbitAdmin} 通过
 * {@code applicationContext.getBeansOfType(Queue.class)} 收集要声明的队列——
 * 这个方法<b>只看 Bean 的直接类型</b>，不会展开 {@code List} 里的元素。
 * <p>
 * 结果：{@code fap.agent.dlq}（直接 Queue Bean）被正确声明，
 * 但所有 {@code agent.*.task.q} / {@code agent.*.result.q} 从未被声明。
 * 消费者启动时找不到队列 → 报 {@code Failed to declare queue(s)}。
 *
 * <h3>为什么用 Declarables</h3>
 * <p>
 * Spring AMQP 2.0+ 提供了 {@link Declarables} 类——一个"声明集合"。
 * RabbitAdmin 会<b>显式识别</b> {@code Declarables} Bean，并展开其中的
 * {@code Queue} / {@code Exchange} / {@code Binding} 元素。
 * <p>
 * 这是"动态声明"的标准做法——固定数量的声明用直接 {@code @Bean}，
 * 动态数量（如本类的按 yml 遍历）用 {@code Declarables}。
 *
 * <h3>为什么 Exchange 仍用直接 Bean</h3>
 * <p>
 * Exchange 数量固定（1 个 topic + 1 个 DLX）——直接 Bean 更清晰。
 * 只有"数量由 yml 决定"的 Queue / Binding 才需要 {@code Declarables}。
 *
 * <h3>为什么 Exchange 声明在 Declarables 之外不冲突</h3>
 * <p>
 * RabbitAdmin 按类型分组声明：
 * <ol>
 *   <li>先声明所有 Exchange（@Bean 的和 Declarables 里的）</li>
 *   <li>再声明所有 Queue</li>
 *   <li>最后声明所有 Binding</li>
 * </ol>
 * <p>
 * 所以 Declarables 里的 Binding 引用 @Bean 里的 Exchange 完全没问题——
 * 声明时 Exchange 已经建好了。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
        name = "app.agent-bus.rabbit.enabled",
        havingValue = "true"
)
public class RabbitTopologyConfig {

    private final RabbitBusProperties props;

    public RabbitTopologyConfig(RabbitBusProperties props) {
        this.props = props;
    }

    // ==================== Message Converter ====================

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    // ==================== 固定 Exchange（直接 Bean）====================

    @Bean
    public TopicExchange agentExchange() {
        log.info("[RabbitTopology] 声明主 Exchange: {}",
                props.getExchange().getName());
        return ExchangeBuilder
                .topicExchange(props.getExchange().getName())
                .durable(props.getExchange().isDurable())
                .autoDelete()
                .build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        log.info("[RabbitTopology] 声明 DLX: {}",
                props.getDeadLetter().getExchange());
        return ExchangeBuilder
                .directExchange(props.getDeadLetter().getExchange())
                .durable(true)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder
                .durable(props.getDeadLetter().getQueue())
                .build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue,
                                     DirectExchange deadLetterExchange) {
        return BindingBuilder
                .bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with(props.getDeadLetter().getRoutingKey());
    }

    // ==================== 动态队列 + 绑定（Declarables）====================

    /**
     * 业务队列 + 绑定——由 yml 的 {@code app.agent-bus.rabbit.queues} 动态决定
     *
     * <h3>为什么用 Declarables 而不是 @Bean List&lt;Queue&gt;</h3>
     * <p>
     * 见类注释——List 类型的 Bean 不会被 RabbitAdmin 展开。
     * Declarables 是 Spring AMQP 专门为"动态声明"提供的容器。
     *
     * <h3>DLX 参数</h3>
     * <p>
     * 每个业务队列都绑定 {@code x-dead-letter-exchange}——消费失败时消息
     * 自动路由到 DLX。这是"消息不丢失"的关键保障。
     */
    @Bean
    public Declarables agentDeclarables(TopicExchange agentExchange) {
        List<Declarable> declarables = new ArrayList<>();

        // DLX 参数——每个业务队列共享
        Map<String, Object> dlxArgs = new HashMap<>();
        dlxArgs.put("x-dead-letter-exchange", props.getDeadLetter().getExchange());
        dlxArgs.put("x-dead-letter-routing-key", props.getDeadLetter().getRoutingKey());

        int queueCount = 0;
        int bindingCount = 0;

        for (Map.Entry<String, RabbitBusProperties.QueueConfig> entry
                : props.getQueues().entrySet()) {

            String roleName = entry.getKey();
            RabbitBusProperties.QueueConfig qc = entry.getValue();

            // ── Task 队列 + 绑定
            String taskQueueName = qc.getTaskQueue();
            if (taskQueueName != null && !taskQueueName.isBlank()) {
                Queue tq = QueueBuilder
                        .durable(taskQueueName)
                        .withArguments(dlxArgs)
                        .build();
                Binding tb = BindingBuilder
                        .bind(tq)
                        .to(agentExchange)
                        .with(props.taskRoutingKey(roleName));
                declarables.add(tq);
                declarables.add(tb);
                queueCount++;
                bindingCount++;
                log.info("[RabbitTopology] 声明 Task Queue: role={}, queue={}, routingKey={}",
                        roleName, taskQueueName, props.taskRoutingKey(roleName));
            }

            // ── Result 队列 + 绑定
            String resultQueueName = qc.getResultQueue();
            if (resultQueueName != null && !resultQueueName.isBlank()) {
                Queue rq = QueueBuilder
                        .durable(resultQueueName)
                        .withArguments(dlxArgs)
                        .build();
                Binding rb = BindingBuilder
                        .bind(rq)
                        .to(agentExchange)
                        .with(props.resultRoutingKey(roleName));
                declarables.add(rq);
                declarables.add(rb);
                queueCount++;
                bindingCount++;
                log.info("[RabbitTopology] 声明 Result Queue: role={}, queue={}, routingKey={}",
                        roleName, resultQueueName, props.resultRoutingKey(roleName));
            }
        }

        log.info("[RabbitTopology] 共声明 {} 个业务队列 + {} 个 Binding",
                queueCount, bindingCount);
        return new Declarables(declarables);
    }
}