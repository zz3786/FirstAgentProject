package org.example.multiagent.aggregation.model;

import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;

import java.util.List;

/**
 * 聚合上下文（D75）
 *
 * <h3>职责</h3>
 * <p>
 * 把所有"聚合策略需要的信息"打包成一个不可变对象——
 * 策略实现只读它，不需要再访问其他 Bean。
 *
 * <h3>为什么带 parentRequest</h3>
 * <p>
 * 有些策略可能需要：
 * <ul>
 *   <li>{@code fullUserId}——做审计或个性化</li>
 *   <li>{@code metadata}——取权重配置、租户信息</li>
 *   <li>{@code requestId}——日志关联</li>
 * </ul>
 * <p>
 * 与其在每个策略里注入一堆依赖，不如一次打包传入——策略实现保持纯函数。
 *
 * <h3>为什么 responses 用 List 而非 Map</h3>
 * <p>
 * 排序信息重要——{@code List} 天然有序（子任务声明顺序）。
 * 按 role 索引的需求用 {@code stream().filter(...)} 解决——
 * 不预优化，保持简单。
 */
public record AggregationContext(
        String goal,
        AgentRequest parentRequest,
        List<AgentResponse> responses
) {

    public AggregationContext {
        if (responses == null) {
            responses = List.of();
        }
    }

    /** 成功的响应数 */
    public long successCount() {
        return responses.stream().filter(AgentResponse::success).count();
    }

    /** 是否有成功响应 */
    public boolean hasSuccess() {
        return responses.stream().anyMatch(AgentResponse::success);
    }

    /** 总响应数 */
    public int size() {
        return responses.size();
    }
}