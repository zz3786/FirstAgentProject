package org.example.multiagent.aggregation.model;

import java.util.Map;

/**
 * 聚合结果（D75）
 *
 * <h3>为什么不用 String 直接返回</h3>
 * <p>
 * 聚合结果需要携带"怎么聚合的"信息：
 * <ul>
 *   <li>用了哪个策略——用于日志/监控/审计</li>
 *   <li>是否降级——策略内部异常时可能走了降级路径</li>
 *   <li>耗时——可观测性</li>
 *   <li>metadata——策略特定的附加信息（如加权分、被选中的响应数）</li>
 * </ul>
 * <p>
 * 返回 {@code String} 会让这些信息丢失——调用方没法知道"这次聚合是真的成功还是降级"
 *
 * <h3>为什么 success 独立于 finalAnswer</h3>
 * <p>
 * {@code finalAnswer} 非空不代表"聚合成功"——可能是降级拼接的。
 * 分开表达让调用方能区分"正常"和"降级"。
 */
public record AggregationResult(
        String finalAnswer,
        String strategyName,
        boolean success,
        long costMs,
        Map<String, Object> metadata
) {

    public AggregationResult {
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    /**
     * 成功结果
     */
    public static AggregationResult ok(String finalAnswer, String strategyName, long costMs) {
        return new AggregationResult(finalAnswer, strategyName, true, costMs, Map.of());
    }

    /**
     * 成功结果——带 metadata
     */
    public static AggregationResult ok(String finalAnswer, String strategyName, long costMs,
                                       Map<String, Object> metadata) {
        return new AggregationResult(finalAnswer, strategyName, true, costMs, metadata);
    }

    /**
     * 失败/降级结果
     */
    public static AggregationResult fail(String finalAnswer, String strategyName,
                                         long costMs, String reason) {
        return new AggregationResult(
                finalAnswer, strategyName, false, costMs,
                Map.of("failReason", reason == null ? "" : reason));
    }

    /** 便捷读取 metadata */
    public String getMetaString(String key) {
        Object v = metadata.get(key);
        return v == null ? null : v.toString();
    }
}