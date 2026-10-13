package org.example.multiagent.aggregation;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.aggregation.model.AggregationContext;
import org.example.multiagent.aggregation.model.AggregationResult;
import org.example.multiagent.config.MultiAgentProperties;
import org.example.multiagent.exception.AgentException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结果聚合门面（D75）
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>按 yml 的 {@code app.multi-agent.aggregation.strategy} 选择策略</li>
 *   <li>调用策略的 aggregate</li>
 *   <li>策略失败时降级到 PLAIN_CONCAT</li>
 * </ol>
 *
 * <h3>为什么用 Map 而不是 switch</h3>
 * <p>
 * 用 {@code Map<String, AggregationStrategy>} 支持：
 * <ul>
 *   <li>策略可以动态扩展——加新策略只加 Bean，不改门面</li>
 *   <li>yml 里配的策略名直接作为 map key——零转换</li>
 *   <li>不需要 if-else / switch——符合开闭原则</li>
 * </ul>
 *
 * <h3>降级链</h3>
 * <pre>
 *   配置的策略（如 LLM_SYNTHESIS）
 *      ↓ 抛异常 / 返回 null
 *   降级到 PLAIN_CONCAT
 *      ↓ 也不存在（不该发生）
 *   返回空字符串 + fail 结果
 * </pre>
 *
 * <h3>为什么不抛异常</h3>
 * <p>
 * 聚合是"最后一步"——它失败意味着用户拿不到答案。
 * 与其抛异常让 Controller 处理，不如尽力返回"部分结果 + 降级标记"。
 * <p>
 * 上层（SupervisorAgent）可以根据 {@code success} 决定是否日志告警。
 */
@Slf4j
public class ResultAggregator {

    /** 降级兜底策略名——策略失败时用 */
    private static final String FALLBACK_STRATEGY = "PLAIN_CONCAT";

    private final Map<String, AggregationStrategy> strategies;
    private final MultiAgentProperties props;

    /**
     * @param strategies 所有已注册的策略——从 Spring 容器注入
     * @param props      多 Agent 配置
     */
    public ResultAggregator(List<AggregationStrategy> strategies,
                            MultiAgentProperties props) {
        // 按 name() 组装 Map——不用 bean name
        Map<String, AggregationStrategy> map = new LinkedHashMap<>();
        for (AggregationStrategy s : strategies) {
            AggregationStrategy existing = map.put(s.name(), s);
            if (existing != null) {
                throw new IllegalStateException(
                        "聚合策略名冲突: " + s.name()
                                + " 同时被 " + existing.getClass().getSimpleName()
                                + " 和 " + s.getClass().getSimpleName() + " 声明");
            }
        }
        this.strategies = map;
        this.props = props;

        log.info("[ResultAggregator] 初始化：策略={}, 默认={}",
                map.keySet(), props.getAggregation().getStrategy());
    }

    /**
     * 执行聚合
     *
     * @param context 聚合上下文
     * @return 聚合结果——永不为 null
     */
    public AggregationResult aggregate(AggregationContext context) {
        if (context == null || !context.hasSuccess()) {
            return AggregationResult.fail(
                    "无可聚合的成功响应", "NONE", 0, "所有 Worker 均失败");
        }

        String strategyName = props.getAggregation().getStrategy();
        AggregationStrategy strategy = strategies.get(strategyName);

        if (strategy == null) {
            log.warn("[ResultAggregator] 未知策略 [{}]，降级为 {}",
                    strategyName, FALLBACK_STRATEGY);
            strategy = strategies.get(FALLBACK_STRATEGY);
        }
        if (strategy == null) {
            // 理论上不该发生——AggregationBeanConfig 会保证 PLAIN_CONCAT 存在
            log.error("[ResultAggregator] 无可用策略（连 {} 都没有）", FALLBACK_STRATEGY);
            return AggregationResult.fail(
                    "无可用聚合策略", "NONE", 0, "配置错误");
        }

        try {
            AggregationResult result = strategy.aggregate(context);
            if (result == null) {
                log.warn("[ResultAggregator] 策略 {} 返回 null——降级", strategyName);
                return fallback(context, "策略返回 null");
            }
            return result;

        } catch (Exception e) {
            log.error("[ResultAggregator] 策略 {} 异常——降级", strategyName, e);
            return fallback(context, e.getMessage());
        }
    }

    // ==================== 内部 ====================

    private AggregationResult fallback(AggregationContext context, String reason) {
        AggregationStrategy plain = strategies.get(FALLBACK_STRATEGY);
        if (plain == null) {
            return AggregationResult.fail(
                    "无可用策略", "NONE", 0, reason);
        }
        try {
            AggregationResult r = plain.aggregate(context);
            if (r != null) {
                return r;
            }
        } catch (Exception e) {
            log.error("[ResultAggregator] 兜底策略也失败", e);
        }
        return AggregationResult.fail(
                "聚合失败", FALLBACK_STRATEGY, 0, reason);
    }
}