package org.example.multiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.conflict.ConflictDetector;
import org.example.multiagent.conflict.ConflictHandler;
import org.example.multiagent.conflict.ConflictResolver;
import org.example.multiagent.conflict.resolver.ArbiterResolver;
import org.example.multiagent.conflict.resolver.MajorityVoteResolver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 冲突处理装配（D76）
 *
 * <h3>装配的 Bean</h3>
 * <pre>
 *   conflictDetectorClient   （在 SupervisorBeanConfig 里定义）
 *   conflictArbiterClient    （在 SupervisorBeanConfig 里定义）
 *       ↓
 *   ConflictDetector
 *   MajorityVoteResolver
 *   ArbiterResolver
 *       ↓
 *   ConflictHandler  ← 门面
 * </pre>
 *
 * <h3>为什么 Detector 和 Arbiter 用不同的 Client</h3>
 * <p>
 * 两者 System Prompt 不同：
 * <ul>
 *   <li>Detector——"分析专家意见的冲突"——客观分析者</li>
 *   <li>Arbiter——"做出权威裁决"——决策者</li>
 * </ul>
 * <p>
 * 分开配置——即使底层是同一个 ChatModel，不同的 System Prompt 会显著影响输出。
 */
@Slf4j
@Configuration
public class ConflictBeanConfig {

    @Bean
    public ConflictDetector conflictDetector(
            @Qualifier("conflictDetectorClient") ChatClient detectorClient) {
        log.info("[Conflict] 装配 ConflictDetector");
        return new ConflictDetector(detectorClient);
    }

    @Bean
    public ConflictResolver majorityVoteResolver() {
        log.info("[Conflict] 装配 MajorityVoteResolver");
        return new MajorityVoteResolver();
    }

    @Bean
    public ConflictResolver arbiterResolver(
            @Qualifier("conflictArbiterClient") ChatClient arbiterClient) {
        log.info("[Conflict] 装配 ArbiterResolver");
        return new ArbiterResolver(arbiterClient);
    }

    @Bean
    public ConflictHandler conflictHandler(
            ConflictDetector detector,
            List<ConflictResolver> resolvers,
            MultiAgentProperties props) {
        return new ConflictHandler(detector, resolvers, props);
    }
}