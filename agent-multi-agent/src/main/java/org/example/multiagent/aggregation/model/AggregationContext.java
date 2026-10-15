package org.example.multiagent.aggregation.model;

import org.example.multiagent.conflict.model.ConflictResolution;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;

import java.util.List;

/**
 * 聚合上下文（D75 建，D76 扩展）
 *
 * <h3>D76 新增字段</h3>
 * <p>
 * {@code conflictResolution}——冲突处理决议。默认 null（未处理过）。
 * 聚合策略可以读取它，在合成时"优先采信决议"。
 *
 * <h3>为什么不用方法参数传入</h3>
 * <p>
 * 决策信息属于"上下文"——它不是"策略要用它做什么"，而是"策略能看到什么"。
 * 塞进 context 让策略自由选择读或不读——更灵活。
 *
 * <h3>为什么保留 3 参构造器</h3>
 * <p>
 * D75 的测试和旧代码用 3 参构造——加 4 参后保留兼容构造器，
 * 避免破坏所有调用点。
 */
public record AggregationContext(
        String goal,
        AgentRequest parentRequest,
        List<AgentResponse> responses,
        ConflictResolution conflictResolution
) {

    public AggregationContext {
        if (responses == null) {
            responses = List.of();
        }
        // conflictResolution 保持 null 语义——"未处理过"
    }

    /**
     * 兼容构造器——D75 签名
     */
    public AggregationContext(String goal, AgentRequest parentRequest,
                              List<AgentResponse> responses) {
        this(goal, parentRequest, responses, null);
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

    /** 是否存在已解决的冲突 */
    public boolean hasResolvedConflict() {
        return conflictResolution != null
                && conflictResolution.hasConflict()
                && conflictResolution.resolution() != null;
    }
}