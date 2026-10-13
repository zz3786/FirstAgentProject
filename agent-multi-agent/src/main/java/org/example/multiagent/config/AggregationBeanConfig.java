package org.example.multiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.aggregation.AggregationStrategy;
import org.example.multiagent.aggregation.ResultAggregator;
import org.example.multiagent.aggregation.strategy.LlmSynthesisStrategy;
import org.example.multiagent.aggregation.strategy.PlainConcatStrategy;
import org.example.multiagent.aggregation.strategy.WeightedConcatStrategy;
import org.example.multiagent.model.AgentRole;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 聚合策略装配（D75）
 *
 * <h3>装配的 Bean</h3>
 * <pre>
 *   LlmSynthesisStrategy     name=LLM_SYNTHESIS      （默认）
 *   WeightedConcatStrategy   name=WEIGHTED_CONCAT
 *   PlainConcatStrategy      name=PLAIN_CONCAT       （兜底）
 *   ResultAggregator         门面
 * </pre>
 *
 * <h3>角色权重配置</h3>
 * <p>
 * WEIGHTED_CONCAT 用 role 权重排序。当前配置：
 * <ul>
 *   <li>FINANCIAL = 1.0（默认）</li>
 *   <li>TECHNICAL = 1.0（默认）</li>
 *   <li>LEGAL = 1.0（默认）</li>
 * </ul>
 * <p>
 * 三权均等——因为业务上没有"财务比技术重要"的明确理由。
 * 如果某客户有特殊偏好，可以后续改成从 yml 读。
 *
 * <h3>为什么不读 yml</h3>
 * <p>
 * "角色权重"是战略决策，不常变。当前阶段硬编码在 Java 里——
 * 改代码只需 1 分钟，且**代码 review** 会带来更谨慎的讨论。
 * <p>
 * yml 化的时机：
 * <ul>
 *   <li>多客户需要差异化权重</li>
 *   <li>运营想 A/B 测试</li>
 * </ul>
 */
@Slf4j
@Configuration
public class AggregationBeanConfig {

    @Bean
    public AggregationStrategy llmSynthesisStrategy(
            @Qualifier("supervisorSynthesizerClient") ChatClient synthesizerClient) {
        log.info("[Aggregation] 装配 LlmSynthesisStrategy（默认策略）");
        return new LlmSynthesisStrategy(synthesizerClient);
    }

    @Bean
    public AggregationStrategy weightedConcatStrategy() {
        Map<AgentRole, Double> weights = Map.of(
                AgentRole.FINANCIAL, 1.0,
                AgentRole.TECHNICAL, 1.0,
                AgentRole.LEGAL,     1.0
        );
        log.info("[Aggregation] 装配 WeightedConcatStrategy（权重={}）", weights);
        return new WeightedConcatStrategy(weights);
    }

    @Bean
    public AggregationStrategy plainConcatStrategy() {
        log.info("[Aggregation] 装配 PlainConcatStrategy（兜底策略）");
        return new PlainConcatStrategy();
    }

    @Bean
    public ResultAggregator resultAggregator(
            java.util.List<AggregationStrategy> strategies,
            MultiAgentProperties props) {
        return new ResultAggregator(strategies, props);
    }
}