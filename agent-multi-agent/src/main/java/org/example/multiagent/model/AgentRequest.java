package org.example.multiagent.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 调用请求（D71）
 *
 * <h3>为什么用 record</h3>
 * <p>
 * 请求一旦发出就不该被修改——Agent 之间传递的应该是"不可变消息"。
 * 需要"改一个字段再发"时用 {@code withXxx} 派生新实例，
 * 这样每一条消息都有明确的溯源（谁从哪条消息派生出来的）。
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code requestId}——本次调用的唯一标识。用于：日志追踪、结果去重、D77 链路 Span 关联</li>
 *   <li>{@code role}——目标 Agent 角色。TaskDispatcher 按它路由</li>
 *   <li>{@code input}——用户输入 / 上游 Agent 传来的任务描述</li>
 *   <li>{@code conversationId}——可选。有值时 Agent 可复用记忆/RAG 上下文</li>
 *   <li>{@code fullUserId}——必传。用于 RBAC 鉴权、审计、多租户隔离</li>
 *   <li>{@code parentTraceId}——可选。D77 追踪用，用于串联"主管→Worker"的 Span</li>
 *   <li>{@code metadata}——扩展字段。D72 会塞入 subtaskId、D74 会塞入 mqMessageId</li>
 *   <li>{@code createdAt}——创建时间戳。用于超时判定、日志排序</li>
 * </ul>
 *
 * <h3>为什么 metadata 用 Map 而非强类型</h3>
 * <p>
 * 不同阶段的元数据差异大（D72 的 subtaskId、D74 的 mqMessageId、D76 的 voteRound），
 * 强类型会导致每加一个字段就要改 record——破坏兼容性。
 * 用 Map 保留灵活性，代价是"取值的类型转换"——但这层转换在调用方（Facade）做一次即可。
 */
public record AgentRequest(
        String requestId,
        AgentRole role,
        String input,
        String conversationId,
        String fullUserId,
        String parentTraceId,
        Map<String, Object> metadata,
        long createdAt
) {

    /**
     * 紧凑构造器——做字段兜底与校验
     * <p>
     * 兜底：{@code requestId} / {@code metadata} / {@code createdAt} 空时给默认值
     * 校验：{@code input} / {@code fullUserId} 为空直接抛异常——这是"业务不变量"
     */
    public AgentRequest {
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        if (role == null) {
            role = AgentRole.SUPERVISOR;
        }
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("AgentRequest.input 不能为空");
        }
        if (fullUserId == null || fullUserId.isBlank()) {
            throw new IllegalArgumentException("AgentRequest.fullUserId 不能为空");
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
     * 最简创建——主管 Agent 接收用户请求时用
     */
    public static AgentRequest of(AgentRole role, String input, String fullUserId) {
        return new AgentRequest(
                null, role, input,
                null, fullUserId, null,
                Map.of(), 0L);
    }

    /**
     * 带会话上下文——Worker Agent 需要复用记忆时用
     */
    public static AgentRequest of(AgentRole role, String input,
                                  String fullUserId, String conversationId) {
        return new AgentRequest(
                null, role, input,
                conversationId, fullUserId, null,
                Map.of(), 0L);
    }

    // ==================== 派生方法 ====================

    /** 派生：换 role——D72 分发时用 */
    public AgentRequest withRole(AgentRole newRole) {
        return new AgentRequest(requestId, newRole, input,
                conversationId, fullUserId, parentTraceId, metadata, createdAt);
    }

    /** 派生：换 input——D72 分发时用（把子任务描述作为新 input） */
    public AgentRequest withInput(String newInput) {
        return new AgentRequest(requestId, role, newInput,
                conversationId, fullUserId, parentTraceId, metadata, createdAt);
    }

    /** 派生：设置 parentTraceId——D77 追踪用 */
    public AgentRequest withParentTraceId(String traceId) {
        return new AgentRequest(requestId, role, input,
                conversationId, fullUserId, traceId, metadata, createdAt);
    }

    /** 派生：追加 metadata（保留原 key） */
    public AgentRequest withMetadata(String key, Object value) {
        Map<String, Object> newMeta = new HashMap<>(metadata);
        newMeta.put(key, value);
        return new AgentRequest(requestId, role, input,
                conversationId, fullUserId, parentTraceId, newMeta, createdAt);
    }

    /** 派生：批量追加 metadata */
    public AgentRequest withMetadata(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, Object> newMeta = new HashMap<>(metadata);
        newMeta.putAll(extra);
        return new AgentRequest(requestId, role, input,
                conversationId, fullUserId, parentTraceId, newMeta, createdAt);
    }

    /** 便捷读取 metadata 的字符串值 */
    public String getMetaString(String key) {
        Object v = metadata.get(key);
        return v == null ? null : v.toString();
    }
}