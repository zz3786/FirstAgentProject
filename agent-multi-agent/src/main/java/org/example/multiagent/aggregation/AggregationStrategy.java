package org.example.multiagent.aggregation;

import org.example.multiagent.aggregation.model.AggregationContext;
import org.example.multiagent.aggregation.model.AggregationResult;

/**
 * 聚合策略接口（D75）
 *
 * <h3>职责</h3>
 * <p>
 * 把"多个 Worker 响应"合成为"一个最终答案"。
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li>{@code name()} 返回稳定的策略名——用于 yml 配置匹配</li>
 *   <li>{@code aggregate()} <b>不应抛异常</b>——内部异常要降级并返回
 *       {@link AggregationResult#fail}</li>
 *   <li>策略必须能处理"部分 Worker 失败"的场景——不是所有响应都成功</li>
 * </ul>
 *
 * <h3>为什么 name() 是显式的而不依赖类名</h3>
 * <p>
 * yml 里配置的是逻辑名（如 {@code LLM_SYNTHESIS}）——不该和 Java 类名耦合。
 * 如果未来换实现（如 {@code LlmSynthesisStrategyV2}），yml 不需要改。
 *
 * <h3>为什么方法名是 aggregate 而非 synthesize / merge</h3>
 * <p>
 * "聚合"（aggregate）是中性的——不预设"合成"还是"拼接"。
 * synthesize 暗示 LLM 合成，merge 暗示多路合并——都会限制语义。
 */
public interface AggregationStrategy {

    /**
     * 策略名——用于 yml 配置匹配。
     * <p>
     * 约定用大写下划线（如 {@code LLM_SYNTHESIS}）——与 yml 里的值一致。
     */
    String name();

    /**
     * 执行聚合
     *
     * @param context 聚合上下文
     * @return 聚合结果——不应为 null
     */
    AggregationResult aggregate(AggregationContext context);
}