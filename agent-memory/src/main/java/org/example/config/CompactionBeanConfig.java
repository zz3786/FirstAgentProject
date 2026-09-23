package org.example.config;

import lombok.extern.slf4j.Slf4j;
import org.example.advisor.CompactingChatMemoryAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAiChatModel (Spring AI 自动配置)
 *         ↓
 *         ├─→ summarizerClient（无 Advisor）
 *         │       ↓
 *         │   CompactingChatMemoryAdvisor
 *         │       ↓
 *         ├─→ redisChatClient（5 个 Advisor）
 *         │       ↓
 *         └─→ plainChatClient（无 Advisor）
 *                 ↓
 *             ChatService 注入 redisChatClient + plainChatClient
 */
@Slf4j
@Configuration
public class CompactionBeanConfig {

    /**
     * 注意：summarizerClient 在 ChatClientConfig 里定义，CompactionBeanConfig 通过 @Qualifier 注入它
     * @param chatMemory
     * @param summarizerClient
     * @param config
     * @return
     */
    @Bean
    public CompactingChatMemoryAdvisor compactingChatMemoryAdvisor(
            ChatMemory chatMemory, //按照规则这里注入的是：redisChatMemory
            @Qualifier("summarizerClient") ChatClient summarizerClient,
            CompactionConfig config) {
        log.info("CompactingChatMemoryAdvisor 装配:");
        log.info("  ChatMemory 类: {}", chatMemory.getClass().getSimpleName());
        log.info("  ChatMemory hashCode: {}", System.identityHashCode(chatMemory));
        log.info("  ChatClient 类: {}", summarizerClient.getClass().getSimpleName());
        return new CompactingChatMemoryAdvisor(chatMemory, summarizerClient, config);
    }
}