package org.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 启动时打印关键 Bean 的名字和类型，便于排查"注入了哪个 Bean"。
 * 生产环境可以删掉或改为 DEBUG 级别。
 */
@Configuration
public class BeanDebugConfig {

    private static final Logger log = LoggerFactory.getLogger(BeanDebugConfig.class);

    @Bean
    public CommandLineRunner printBeans(ApplicationContext ctx) {
        return args -> {
            log.info("========================================");
            log.info("关键 Bean 清单");
            log.info("========================================");

            printBeansOfType(ctx, ChatMemory.class);
            printBeansOfType(ctx, ChatMemoryRepository.class);
            printBeansOfType(ctx, ChatClient.class);

            log.info("========================================");
        };
    }

    private <T> void printBeansOfType(ApplicationContext ctx, Class<T> type) {
        Map<String, T> beans = ctx.getBeansOfType(type);
        if (beans.isEmpty()) {
            log.info("[{}] 无", type.getSimpleName());
            return;
        }
        log.info("[{}]", type.getSimpleName());
        beans.forEach((name, bean) ->
                log.info("  └─ Bean名: {} | 实现类: {} | hashCode: {}",
                        name,
                        bean.getClass().getSimpleName(),
                        System.identityHashCode(bean)));   // ← 加这行
    }
}