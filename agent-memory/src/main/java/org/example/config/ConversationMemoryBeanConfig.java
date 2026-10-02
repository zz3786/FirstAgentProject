package org.example.config;

import lombok.extern.slf4j.Slf4j;
import org.example.advisor.ConversationMemoryAdvisor;
import org.example.advisor.ConversationRetrievalAdvisor;
import org.example.memory.ConversationMemoryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * D50 Advisor 装配
 * <p>
 * 为什么单独一个 Config 而不直接 @Component：
 * <ul>
 *   <li>Advisor 是"纯逻辑"类，不依赖 Spring 注解——便于单测 new 出来</li>
 *   <li>装配显式化，启动时日志能看到是否注册成功</li>
 *   <li>与 CompactionBeanConfig 风格保持一致</li>
 * </ul>
 */
@Slf4j
@Configuration
public class ConversationMemoryBeanConfig {

    @Bean
    public ConversationRetrievalAdvisor conversationRetrievalAdvisor(
            ConversationMemoryService memoryService) {
        log.info("装配 ConversationRetrievalAdvisor（order=210）");
        return new ConversationRetrievalAdvisor(memoryService);
    }

    @Bean
    public ConversationMemoryAdvisor conversationMemoryAdvisor(
            ConversationMemoryService memoryService,
            StringRedisTemplate redis) {
        log.info("装配 ConversationMemoryAdvisor（order=250）");
        return new ConversationMemoryAdvisor(memoryService, redis);
    }
}