package org.example.multiagent.bus;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.exception.AgentException;
import org.example.multiagent.model.AgentMessage;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 内存实现的消息总线（D74）
 *
 * <h3>用途</h3>
 * <p>
 * 单元测试——不依赖 RabbitMQ 实例。业务代码在测试里也能跑通整条
 * "发消息 → 处理 → 回执"的链路。
 *
 * <h3>原理</h3>
 * <p>
 * 用 {@code messageHandler} 模拟"Worker 消费消息"——
 * 业务方在测试里注册一个 handler：
 * <pre>
 *   bus.setHandler(message -> {
 *       // 模拟 Worker 处理
 *       return AgentMessage.result(message, "FINANCIAL", "处理结果", ...);
 *   });
 * </pre>
 *
 * <p>
 * {@code sendAndWait} 时同步调用 handler 拿到结果——立刻返回，不用真等。
 *
 * <h3>为什么不实现真正的"异步"内存总线</h3>
 * <p>
 * 单测关心"业务逻辑正确"，不关心"MQ 语义"。同步调用模拟异步更简单——
 * 减少测试的不确定性（flaky test）。
 * <p>
 * 如果未来需要"真异步 + 内存"测试（如验证并发），可以加一个异步版本。
 * 当前保持简单。
 */
@Slf4j
public class InMemoryAgentMessageBus implements AgentMessageBus {

    /** 消息处理器——业务方注册，模拟消费逻辑 */
    private volatile Function<AgentMessage, AgentMessage> handler;

    /** 是否可用——测试里可以手动切换验证降级 */
    private volatile boolean available = true;

    public InMemoryAgentMessageBus() {
        log.info("[InMemoryBus] 初始化——仅用于测试");
    }

    /**
     * 注册处理器——模拟 Worker 消费消息
     */
    public void setHandler(Function<AgentMessage, AgentMessage> handler) {
        this.handler = handler;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public void publish(AgentMessage message) {
        if (!available) {
            throw new AgentException(AgentException.Code.BUS_ERROR, null,
                    "InMemoryBus 不可用");
        }
        if (handler == null) {
            log.warn("[InMemoryBus] publish 但未注册 handler，消息被丢弃: messageId={}",
                    message.messageId());
            return;
        }
        // 单向发布也走 handler（异步处理场景）
        try {
            handler.apply(message);
        } catch (Exception e) {
            log.warn("[InMemoryBus] handler 处理异常: {}", e.getMessage());
        }
    }

    @Override
    public AgentMessage sendAndWait(AgentMessage task, Duration timeout) {
        if (!available) {
            throw new AgentException(AgentException.Code.BUS_ERROR, null,
                    "InMemoryBus 不可用");
        }
        if (handler == null) {
            throw new AgentException(AgentException.Code.BUS_ERROR, null,
                    "未注册 handler——无法处理消息");
        }

        try {
            long start = System.currentTimeMillis();
            AgentMessage result = handler.apply(task);
            long cost = System.currentTimeMillis() - start;

            // 模拟超时——如果 handler 处理超时
            if (cost > timeout.toMillis()) {
                throw new TimeoutException("处理超时: " + cost + "ms");
            }
            return result;

        } catch (TimeoutException e) {
            throw new AgentException(AgentException.Code.TIMEOUT, null,
                    "等待响应超时: " + e.getMessage(), e);
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException(AgentException.Code.BUS_ERROR, null,
                    "sendAndWait 异常: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }
}