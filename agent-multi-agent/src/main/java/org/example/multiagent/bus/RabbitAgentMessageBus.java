package org.example.multiagent.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.bus.config.RabbitBusProperties;
import org.example.multiagent.exception.AgentException;
import org.example.multiagent.model.AgentMessage;
import org.example.multiagent.model.MessageType;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ 实现的消息总线（D74）
 *
 * <h3>核心流程</h3>
 * <pre>
 *   sendAndWait(task, timeout):
 *     ① correlationRegistry.register(correlationId)
 *     ② rabbitTemplate.convertAndSend(exchange, taskRoutingKey, task)
 *     ③ future.get(timeout)   ← 阻塞等待
 *     ④ return future.getNow()
 * </pre>
 *
 * <h3>为什么用 correlationId 而非临时 reply queue</h3>
 * <p>
 * RabbitMQ 的经典 RPC 模式是"每个请求一个临时 reply queue"——
 * 通过 {@code replyTo} + {@code correlationId} 关联。
 * <p>
 * <b>本实现没有采用</b>，理由：
 * <ul>
 *   <li>临时 queue 创建/销毁有开销——高 QPS 场景会成为瓶颈</li>
 *   <li>RabbitMQ 对临时 queue 数量有上限</li>
 *   <li>我们的 result queue 是"每角色一个"固定队列——天然支持 correlationId 复用</li>
 * </ul>
 *
 * <h3>超时处理</h3>
 * <p>
 * {@code future.get(timeout)} 抛 TimeoutException → 转 AgentException(BUS_ERROR)。
 * 同时 cancel 注册的 correlation——防止内存泄漏。
 *
 * <h3>RabbitTemplate 的 convertAndSend 序列化</h3>
 * <p>
 * 依赖 Spring AMQP 的 Jackson2JsonMessageConverter——
 * 在 {@code RabbitTopologyConfig} 里配置。
 * <p>
 * 消息体会被序列化成 JSON，消费端反序列化回 {@link AgentMessage}。
 *
 * <h3>为什么不用 @RabbitListener 做接收</h3>
 * <p>
 * 接收需要监听"每个角色的 result queue"——角色是 yml 动态配置的。
 * {@code @RabbitListener} 的 queue 名不支持动态——用
 * {@code RabbitConsumerConfig} 里的 {@code SimpleMessageListenerContainer}
 * 手动注册。
 */
@Slf4j
public class RabbitAgentMessageBus implements AgentMessageBus {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitBusProperties props;
    private final MessageCorrelationRegistry correlationRegistry;
    private final ObjectMapper objectMapper;

    public RabbitAgentMessageBus(RabbitTemplate rabbitTemplate,
                                 RabbitBusProperties props,
                                 MessageCorrelationRegistry correlationRegistry,
                                 ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.props = props;
        this.correlationRegistry = correlationRegistry;
        this.objectMapper = objectMapper;
        log.info("[RabbitBus] 初始化: exchange={}, queues={}",
                props.getExchange().getName(), props.getQueues().keySet());
    }

    // ==================== 单向发布 ====================

    @Override
    public void publish(AgentMessage message) {
        if (message == null) {
            return;
        }

        String routingKey = routingKeyFor(message);

        try {

            log.info("[RabbitBus] 准备发送: type={}, from={}, to={}, messageId={}, " +
                            "correlationId={}, routingKey={}, exchange={}",
                    message.type(), message.fromRole(), message.toRole(),
                    message.messageId(), message.correlationId(),
                    routingKey, props.getExchange().getName());

            rabbitTemplate.convertAndSend(
                    props.getExchange().getName(),
                    routingKey,
                    message,
                    amqpMessage -> {
                        MessageProperties p = amqpMessage.getMessageProperties();
                        p.setContentType("application/json");
                        p.setMessageId(message.messageId());
                        if (message.correlationId() != null) {
                            p.setCorrelationId(message.correlationId());
                        }
                        return amqpMessage;
                    });

            log.debug("[RabbitBus] 已发送: type={}, from={}, to={}, messageId={}, routingKey={}",
                    message.type(), message.fromRole(), message.toRole(),
                    message.messageId(), routingKey);

        } catch (Exception e) {
            log.error("[RabbitBus] 发送失败: messageId={}, routingKey={}",
                    message.messageId(), routingKey, e);
            throw new AgentException(AgentException.Code.BUS_ERROR, null,
                    "发送消息失败: " + e.getMessage(), e);
        }
    }

    // ==================== 同步请求-响应 ====================

    @Override
    public AgentMessage sendAndWait(AgentMessage task, Duration timeout) {
        if (task.type() != MessageType.TASK) {
            throw new IllegalArgumentException(
                    "sendAndWait 只支持 TASK 消息，实际: " + task.type());
        }

        String correlationId = task.correlationId();

        // ① 注册等待
        CompletableFuture<AgentMessage> future = correlationRegistry.register(correlationId);

        try {
            // ② 发送
            publish(task);

            // ③ 等待
            AgentMessage result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            log.info("[RabbitBus] 收到响应: correlationId={}, type={}, costMs={}",
                    correlationId, result.type(), result.workerCostMs());

            return result;

        } catch (TimeoutException e) {
            correlationRegistry.cancel(correlationId);
            throw new AgentException(AgentException.Code.TIMEOUT, null,
                    "等待响应超时: correlationId=" + correlationId
                            + ", timeout=" + timeout.toMillis() + "ms", e);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            correlationRegistry.cancel(correlationId);
            throw new AgentException(AgentException.Code.BUS_ERROR, null,
                    "等待被中断: correlationId=" + correlationId, e);

        } catch (AgentException e) {
            correlationRegistry.cancel(correlationId);
            throw e;

        } catch (Exception e) {
            correlationRegistry.cancel(correlationId);
            throw new AgentException(AgentException.Code.BUS_ERROR, null,
                    "sendAndWait 异常: " + e.getMessage(), e);

        } finally {
            // ④ 双保险——无论何种路径都清理
            correlationRegistry.cancel(correlationId);
        }
    }

    // ==================== 可用性 ====================

    @Override
    public boolean isAvailable() {
        try {
            // 用 RabbitTemplate 的 execute 检查连接——内部会拿 Connection
            Boolean available = rabbitTemplate.execute(channel -> true);
            return Boolean.TRUE.equals(available);
        } catch (Exception e) {
            log.debug("[RabbitBus] 连接不可用: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 内部 ====================

    /**
     * 按消息类型 + 目标角色生成 routing key
     * <ul>
     *   <li>TASK → {@code agent.{toRole}.task}</li>
     *   <li>RESULT / ERROR → {@code agent.{fromRole}.result}</li>
     * </ul>
     * <p>
     * RESULT / ERROR 走"发送者角色的 result queue"——
     * 主管角色接收时也会注册 result queue（未来 D75 引入独立主管队列）。
     */
    private String routingKeyFor(AgentMessage message) {
        return switch (message.type()) {
            case TASK -> props.taskRoutingKey(message.toRole());
            case RESULT, ERROR -> props.resultRoutingKey(message.fromRole());
        };
    }
}