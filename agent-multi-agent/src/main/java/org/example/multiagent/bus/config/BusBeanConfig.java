package org.example.multiagent.bus.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.bus.AgentMessageBus;
import org.example.multiagent.bus.MessageCorrelationRegistry;
import org.example.multiagent.bus.RabbitAgentMessageBus;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息总线 Bean 装配（D74）
 *
 * <h3>开关逻辑</h3>
 * <pre>
 *   app.agent-bus.rabbit.enabled=true   → 装配 RabbitAgentMessageBus
 *   app.agent-bus.rabbit.enabled=false  → 装配 InMemoryAgentMessageBus
 * </pre>
 * <p>
 * 用 InMemory 兜底——保证 {@code AgentMessageConsumer} 等依赖 Bus 的组件
 * 在没有 RabbitMQ 时也能启动（虽然 MQ 通信不可用，但业务代码不崩）。
 *
 * <h3>为什么用 @ConditionalOnProperty 而非 @ConditionalOnBean</h3>
 * <p>
 * {@code @ConditionalOnProperty} 是"配置驱动"——运维明确开关。
 * {@code @ConditionalOnBean(RabbitTemplate.class)} 是"依赖驱动"——
 * 但 RabbitTemplate 在 enabled=false 时也可能被 Spring Boot 自动配置
 * （如果 spring.rabbitmq.host 有配置）。配置驱动更明确。
 */
@Slf4j
@Configuration
public class BusBeanConfig {

    /**
     * RabbitMQ 实现——enabled=true 时启用
     */
    @Bean
    @ConditionalOnProperty(
            name = "app.agent-bus.rabbit.enabled",
            havingValue = "true"
    )
    public AgentMessageBus rabbitAgentMessageBus(
            RabbitTemplate rabbitTemplate,
            RabbitBusProperties props,
            MessageCorrelationRegistry correlationRegistry,
            ObjectMapper objectMapper) {
        log.info("[BusBeanConfig] 装配 RabbitAgentMessageBus");
        return new RabbitAgentMessageBus(
                rabbitTemplate, props, correlationRegistry, objectMapper);
    }

    /**
     * InMemory 实现——enabled 不为 true 时兜底
     * <p>
     * 仅用于单元测试和本地无 MQ 场景——不要在生产启用。
     */
    @Bean
    @ConditionalOnMissingBean(AgentMessageBus.class)
    public AgentMessageBus inMemoryAgentMessageBus() {
        log.warn("[BusBeanConfig] 装配 InMemoryAgentMessageBus——仅用于测试，" +
                "生产请启用 app.agent-bus.rabbit.enabled=true");
        return new org.example.multiagent.bus.InMemoryAgentMessageBus();
    }
}