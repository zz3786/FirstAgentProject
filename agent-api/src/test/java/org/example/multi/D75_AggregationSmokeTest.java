package org.example.multiagent;

import org.example.multiagent.aggregation.ResultAggregator;
import org.example.multiagent.aggregation.model.AggregationContext;
import org.example.multiagent.aggregation.model.AggregationResult;
import org.example.multiagent.config.MultiAgentProperties;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D75 聚合策略冒烟测试
 *
 * <h3>验证 3 件事</h3>
 * <ol>
 *   <li>三种策略都能跑出结果</li>
 *   <li>策略可以按配置切换</li>
 *   <li>异常时能降级到 PLAIN_CONCAT</li>
 * </ol>
 */
@SpringBootTest
class D75_AggregationSmokeTest {

    @Autowired
    private ResultAggregator aggregator;

    @Autowired
    private MultiAgentProperties props;

    /** 构造测试用的三个 Worker 响应 */
    private AggregationContext buildContext() {
        AgentRequest req = AgentRequest.of(
                AgentRole.SUPERVISOR,
                "评估客户管理系统",
                "hospital-a:user-alice");

        List<AgentResponse> responses = List.of(
                AgentResponse.ok("req-1", AgentRole.FINANCIAL,
                        "客户管理系统的年均成本约 30 万元，含软件许可 20 万、运维 10 万。",
                        1000, 0, 0.9),
                AgentResponse.ok("req-2", AgentRole.TECHNICAL,
                        "建议采用 Spring Boot + MySQL 架构，部署简单，团队熟悉度高。",
                        1000, 0, 0.85),
                AgentResponse.ok("req-3", AgentRole.LEGAL,
                        "客户数据采集需符合《个人信息保护法》，必须获得用户明示同意。",
                        1000, 0, 0.8)
        );

        return new AggregationContext("评估客户管理系统", req, responses);
    }

    @Test
    @DisplayName("D75-1：默认策略（LLM_SYNTHESIS）应产出连贯答案")
    void testDefaultStrategy() {
        System.out.println("========== D75-1 默认策略 ==========");
        System.out.println("当前配置策略 = " + props.getAggregation().getStrategy());

        AggregationResult result = aggregator.aggregate(buildContext());

        System.out.println("strategyName = " + result.strategyName());
        System.out.println("success      = " + result.success());
        System.out.println("costMs       = " + result.costMs());
        System.out.println("--- 输出 ---");
        System.out.println(result.finalAnswer());
        System.out.println("=====================================");

        assertTrue(result.success(), "应成功");
        assertNotNull(result.finalAnswer(), "应有输出");
        assertFalse(result.finalAnswer().isBlank(), "输出不应为空");
    }

    @Test
    @DisplayName("D75-2：切换策略——观察不同的聚合方式")
    void testSwitchStrategy() {
        // 手工切换成 WEIGHTED_CONCAT——临时改属性
        String original = props.getAggregation().getStrategy();
        try {
            props.getAggregation().setStrategy("WEIGHTED_CONCAT");

            AggregationResult result = aggregator.aggregate(buildContext());

            System.out.println("========== D75-2 加权拼接 ==========");
            System.out.println("strategyName = " + result.strategyName());
            System.out.println("success      = " + result.success());
            System.out.println("metadata     = " + result.metadata());
            System.out.println("--- 输出 ---");
            System.out.println(result.finalAnswer());
            System.out.println("====================================");

            assertEquals("WEIGHTED_CONCAT", result.strategyName());
            assertTrue(result.success());

        } finally {
            props.getAggregation().setStrategy(original);
        }
    }

    @Test
    @DisplayName("D75-3：PLAIN_CONCAT——无 LLM 依赖，纯拼接")
    void testPlainConcat() {
        String original = props.getAggregation().getStrategy();
        try {
            props.getAggregation().setStrategy("PLAIN_CONCAT");

            AggregationResult result = aggregator.aggregate(buildContext());

            System.out.println("========== D75-3 简单拼接 ==========");
            System.out.println("strategyName = " + result.strategyName());
            System.out.println("--- 输出 ---");
            System.out.println(result.finalAnswer());
            System.out.println("====================================");

            assertEquals("PLAIN_CONCAT", result.strategyName());

        } finally {
            props.getAggregation().setStrategy(original);
        }
    }

    @Test
    @DisplayName("D75-4：未知策略——应降级到 PLAIN_CONCAT")
    void testUnknownStrategyFallback() {
        String original = props.getAggregation().getStrategy();
        try {
            props.getAggregation().setStrategy("NOT_EXIST_STRATEGY");

            AggregationResult result = aggregator.aggregate(buildContext());

            System.out.println("========== D75-4 未知策略降级 ==========");
            System.out.println("strategyName = " + result.strategyName());
            System.out.println("success      = " + result.success());
            System.out.println("=======================================");

            // 降级后是 PLAIN_CONCAT
            assertEquals("PLAIN_CONCAT", result.strategyName(),
                    "未知策略应降级到 PLAIN_CONCAT");

        } finally {
            props.getAggregation().setStrategy(original);
        }
    }
}