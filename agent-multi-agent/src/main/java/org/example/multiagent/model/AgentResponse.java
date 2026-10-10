package org.example.multiagent.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 调用响应（D71）
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code requestId}——对应哪次请求。用于结果匹配、去重</li>
 *   <li>{@code role}——产生这个响应的 Agent 角色</li>
 *   <li>{@code success}——是否成功。失败时 {@code output=null}、{@code error} 非空</li>
 *   <li>{@code output}——成功时的答案文本</li>
 *   <li>{@code error}——失败原因。业务上失败（如超时）也走这里，不用抛异常</li>
 *   <li>{@code costMs}——本次调用耗时（含反思）</li>
 *   <li>{@code reflectionRounds}——实际发生的反思轮次（0 表示未反思）</li>
 *   <li>{@code qualityScore}——最终答案的质量分（-1 表示未评估）</li>
 *   <li>{@code metadata}——扩展。D74 塞 mqMessageId、D75 塞 aggregationSource</li>
 *   <li>{@code createdAt}——完成时间戳</li>
 * </ul>
 *
 * <h3>为什么失败不用异常</h3>
 * <p>
 * 多 Agent 场景下"某个 Worker 失败"是<b>正常的业务事件</b>——
 * 主管 Agent 需要决策"是重试、换一个 Worker、还是接受部分结果"。
 * 抛异常会打断主管的决策逻辑，用 {@code success=false} 让主管自己处理。
 *
 * <p>
 * 只有"编程错误"（如 role 为 null）才抛异常。
 */
public record AgentResponse(
        String requestId,
        AgentRole role,
        boolean success,
        String output,
        String error,
        long costMs,
        int reflectionRounds,
        double qualityScore,
        Map<String, Object> metadata,
        long createdAt
) {

    public AgentResponse {
        if (requestId == null) {
            throw new IllegalArgumentException("AgentResponse.requestId 不能为空");
        }
        if (role == null) {
            throw new IllegalArgumentException("AgentResponse.role 不能为空");
        }
        if (success && (output == null || output.isBlank())) {
            throw new IllegalArgumentException("成功响应必须包含 output");
        }
        if (!success && (error == null || error.isBlank())) {
            throw new IllegalArgumentException("失败响应必须包含 error");
        }
        if (metadata == null) {
            metadata = Map.of();
        }
        if (createdAt <= 0) {
            createdAt = System.currentTimeMillis();
        }
    }

    // ==================== 静态工厂 ====================

    /**
     * 成功响应
     */
    public static AgentResponse ok(String requestId, AgentRole role, String output,
                                   long costMs, int reflectionRounds, double qualityScore) {
        return new AgentResponse(
                requestId, role, true, output, null,
                costMs, reflectionRounds, qualityScore,
                Map.of(), 0L);
    }

    /**
     * 成功响应——带 metadata
     */
    public static AgentResponse ok(String requestId, AgentRole role, String output,
                                   long costMs, int reflectionRounds, double qualityScore,
                                   Map<String, Object> metadata) {
        return new AgentResponse(
                requestId, role, true, output, null,
                costMs, reflectionRounds, qualityScore,
                metadata, 0L);
    }

    /**
     * 失败响应
     */
    public static AgentResponse fail(String requestId, AgentRole role,
                                     String error, long costMs) {
        return new AgentResponse(
                requestId, role, false, null, error,
                costMs, 0, -1.0,
                Map.of(), 0L);
    }

    /**
     * 失败响应——带 metadata
     */
    public static AgentResponse fail(String requestId, AgentRole role,
                                     String error, long costMs,
                                     Map<String, Object> metadata) {
        return new AgentResponse(
                requestId, role, false, null, error,
                costMs, 0, -1.0,
                metadata, 0L);
    }

    // ==================== 派生 ====================

    /** 派生：追加 metadata（保留原 key） */
    public AgentResponse withMetadata(String key, Object value) {
        Map<String, Object> newMeta = new HashMap<>(metadata);
        newMeta.put(key, value);
        return new AgentResponse(
                requestId, role, success, output, error,
                costMs, reflectionRounds, qualityScore,
                newMeta, createdAt);
    }

    /** 便捷：读取 metadata 的字符串值 */
    public String getMetaString(String key) {
        Object v = metadata.get(key);
        return v == null ? null : v.toString();
    }
}