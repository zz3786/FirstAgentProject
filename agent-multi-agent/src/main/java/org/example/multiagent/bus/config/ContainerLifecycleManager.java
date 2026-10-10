package org.example.multiagent.bus.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RabbitMQ 消费者容器生命周期管理器（D74 修复）
 *
 * <h3>为什么需要这个类</h3>
 * <p>
 * D74 首版用 {@code @Bean List<SimpleMessageListenerContainer>} 声明容器——
 * 期望 Spring 自动启动它们。但<b>这不会发生</b>：
 * <ul>
 *   <li>Spring 的 {@code LifecycleProcessor} 只处理"<b>直接实现</b>
 *       {@code SmartLifecycle}</b> 的 Bean"</li>
 *   <li>{@code List<SimpleMessageListenerContainer>} 这个 Bean 的类型是 List——
 *       List 本身不实现 {@code SmartLifecycle}</li>
 *   <li>List 里的元素虽然各自是 {@code SmartLifecycle}，但**它们不是 Bean**——
 *       只是普通对象，LifecycleProcessor 看不到</li>
 * </ul>
 * <p>
 * 结果：容器创建了、也设置了 autoStartup=true——但**没有人调用它们的 start()**。
 * 消费者一直处于"未启动"状态，消息发到队列里堆积，无人消费。
 *
 * <h3>它做什么</h3>
 * <p>
 * 实现 {@code SmartLifecycle}——Spring 会：
 * <ol>
 *   <li>容器启动时调 {@link #start()}——这里批量启动所有容器</li>
 *   <li>容器关闭时调 {@link #stop()}——这里批量停止所有容器</li>
 * </ol>
 *
 * <h3>Phase 为什么是 Integer.MAX_VALUE - 1</h3>
 * <p>
 * Phase 决定启动顺序：<b>phase 越小越先启动</b>。
 * <ul>
 *   <li>{@code RabbitTemplate} 等基础组件通常 phase 较小——先启动</li>
 *   <li>本类 phase = MAX_VALUE - 1——在所有基础组件之后启动</li>
 *   <li>保证消费者容器启动时，ConnectionFactory / RabbitAdmin 已就绪</li>
 * </ul>
 * <p>
 * 用 {@code MAX_VALUE - 1} 而不是 {@code MAX_VALUE}——
 * 给"最后的关闭钩子"留位置。
 *
 * <h3>为什么不返回 {@code @Bean} 单独注册每个容器</h3>
 * <p>
 * 队列数量由 yml 决定（动态），无法用固定数量的 {@code @Bean} 方法声明。
 * 一个管理器包装所有容器是标准做法。
 */
@Slf4j
@Component
public class ContainerLifecycleManager implements SmartLifecycle {

    private final List<SimpleMessageListenerContainer> containers;
    private volatile boolean running = false;

    public ContainerLifecycleManager(List<SimpleMessageListenerContainer> containers) {
        this.containers = containers;
        log.info("[ContainerLifecycle] 收到 {} 个消费者容器待管理", containers.size());
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        log.info("[ContainerLifecycle] 启动 {} 个 RabbitMQ 消费者容器", containers.size());
        int success = 0;
        int failed = 0;
        for (SimpleMessageListenerContainer container : containers) {
            try {
                container.start();
                success++;
                log.info("[ContainerLifecycle] ✅ 容器已启动: queues={}",
                        String.join(",", container.getQueueNames()));
            } catch (Exception e) {
                failed++;
                log.error("[ContainerLifecycle] ❌ 容器启动失败: queues={}",
                        String.join(",", container.getQueueNames()), e);
            }
        }
        running = true;
        log.info("[ContainerLifecycle] 完成: 成功={}, 失败={}", success, failed);
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        log.info("[ContainerLifecycle] 停止 {} 个消费者容器", containers.size());
        for (SimpleMessageListenerContainer container : containers) {
            try {
                container.stop();
            } catch (Exception e) {
                log.warn("[ContainerLifecycle] 容器停止异常: queues={}",
                        String.join(",", container.getQueueNames()), e);
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // ★ 在所有基础组件之后启动——保证 ConnectionFactory 已就绪
        return Integer.MAX_VALUE - 1;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}