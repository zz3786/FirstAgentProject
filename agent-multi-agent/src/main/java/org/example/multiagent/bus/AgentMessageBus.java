package org.example.multiagent.bus;

import org.example.multiagent.model.AgentMessage;

import java.time.Duration;

/**
 * Agent 消息总线（D74）
 *
 * <h3>设计目标</h3>
 * <p>
 * 把"Agent 间通信"抽象成一个接口——业务代码只依赖接口，
 * 不感知底层是 RabbitMQ、Kafka 还是进程内队列。
 *
 * <h3>为什么两个方法：publish / sendAndWait</h3>
 * <ul>
 *   <li>{@code publish}——单向发消息，不等回执。适合"广播"、"通知"场景</li>
 *   <li>{@code sendAndWait}——发送 + 等待关联回执。适合"请求-响应"场景</li>
 * </ul>
 * <p>
 * 两者语义清晰、职责单一——不需要 {@code sendAsync} 之类的中间态。
 *
 * <h3>实现清单</h3>
 * <ul>
 *   <li>{@code RabbitAgentMessageBus}——生产实现</li>
 *   <li>{@code InMemoryAgentMessageBus}——单测实现（无 MQ 依赖）</li>
 *   <li>未来可加 {@code KafkaAgentMessageBus} 等</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>
 * 实现必须线程安全——{@code sendAndWait} 会被多个业务线程并发调用。
 */
public interface AgentMessageBus {

    /**
     * 单向发布消息——不等待回执
     *
     * @param message 消息
     */
    void publish(AgentMessage message);

    /**
     * 发送 TASK 消息并等待关联的 RESULT/ERROR 消息
     *
     * @param task    任务消息（类型必须是 TASK）
     * @param timeout 最长等待时长
     * @return 回执消息（RESULT 或 ERROR）
     * @throws org.example.multiagent.exception.AgentException 等待超时或发送失败
     */
    AgentMessage sendAndWait(AgentMessage task, Duration timeout);

    /**
     * 总线是否可用
     * <p>
     * 用于降级判断——不可用时业务代码可以走"直接调用"而非"发消息"。
     */
    boolean isAvailable();
}