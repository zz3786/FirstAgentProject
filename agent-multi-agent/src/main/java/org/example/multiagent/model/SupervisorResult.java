package org.example.multiagent.model;

import java.util.List;
import java.util.Map;

/**
 * 主管 Agent 执行结果（D72）
 *
 * <h3>与 AgentResponse 的关系</h3>
 * <p>
 * {@code SupervisorResult} 是"面向调用方的完整结果"，包含：
 * <ul>
 *   <li>{@code requestId}——关联的请求</li>
 *   <li>{@code taskGraph}——LLM 拆解出的任务图</li>
 *   <li>{@code workerResponses}——每个 Worker 的独立响应</li>
 *   <li>{@code finalAnswer}——主管视角的汇总答案</li>
 *   <li>{@code workerSuccessRate}——Worker 成功率（0.0~1.0）</li>
 * </ul>
 *
 * <p>
 * 调用方（如 Controller）通常只需要 {@code finalAnswer}——
 * 但 debug / 日志 / 前端"展示思考过程"时，
 * {@code taskGraph} 和 {@code workerResponses} 是宝贵信息。
 *
 * <h3>为什么不用 AgentResponse 承载</h3>
 * <p>
 * {@code AgentResponse} 是"单个 Agent 的响应"——扁平结构。
 * {@code SupervisorResult} 是"多个 Agent 的聚合"——有嵌套。
 * 强塞进 AgentResponse 会让 {@code metadata} 变成大杂烩。
 *
 * <h3>D75 之后会怎么变</h3>
 * <p>
 * D75 会引入"结果聚合策略"（LLM 合成 / 加权投票等），
 * {@code finalAnswer} 的生成会从"简单拼接"演化为"策略驱动"。
 * 当前 D72 用最简的"汇总 prompt"生成。
 */
public record SupervisorResult(
        String requestId,
        TaskGraph taskGraph,
        List<AgentResponse> workerResponses,
        String finalAnswer,
        double workerSuccessRate,
        long totalCostMs,
        Map<String, Object> metadata
) {

    public SupervisorResult {
        if (metadata == null) {
            metadata = Map.of();
        }
        if (workerResponses == null) {
            workerResponses = List.of();
        }
    }

    /**
     * 静态工厂——成功
     */
    public static SupervisorResult ok(String requestId,
                                      TaskGraph taskGraph,
                                      List<AgentResponse> workerResponses,
                                      String finalAnswer,
                                      long totalCostMs) {
        long successCount = workerResponses.stream()
                .filter(AgentResponse::success)
                .count();
        double rate = workerResponses.isEmpty()
                ? 0.0
                : (double) successCount / workerResponses.size();
        return new SupervisorResult(
                requestId, taskGraph, workerResponses,
                finalAnswer, rate, totalCostMs, Map.of());
    }

    /**
     * 静态工厂——失败
     */
    public static SupervisorResult fail(String requestId, String error, long totalCostMs) {
        return new SupervisorResult(
                requestId, new TaskGraph("失败", List.of()),
                List.of(), error, 0.0, totalCostMs, Map.of());
    }

    /** 是否所有 Worker 都成功 */
    public boolean allWorkersSucceeded() {
        return !workerResponses.isEmpty()
                && workerResponses.stream().allMatch(AgentResponse::success);
    }

    /** 便捷读取 metadata 的字符串值 */
    public String getMetaString(String key) {
        Object v = metadata.get(key);
        return v == null ? null : v.toString();
    }
}