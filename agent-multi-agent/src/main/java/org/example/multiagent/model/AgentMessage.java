package org.example.multiagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 间消息（D74）
 *
 * <h3>为什么 TASK 和 RESULT 用同一个 record</h3>
 * <p>
 * 好处：
 * <ul>
 *   <li>序列化/反序列化只需一套代码</li>
 *   <li>RabbitMQ 消息体结构统一——运维用一套 schema 解析所有消息</li>
 *   <li>correlationId 关联请求-响应天然对齐</li>
 * </ul>
 * <p>
 * 代价：字段冗余（TASK 用不到 {@code workerCostMs}，RESULT 用不到 {@code input}）。
 * 但这层冗余换来的是"一套代码处理所有消息"——值。
 *
 * <h3>字段职责</h3>
 * <ul>
 *   <li>{@code messageId}——唯一标识，用于幂等去重</li>
 *   <li>{@code correlationId}——请求-响应关联。TASK 和它的 RESULT 用同一个值</li>
 *   <li>{@code type}——消息类型</li>
 *   <li>{@code fromRole} / {@code toRole}——发送方 / 接收方角色（字符串，非枚举）</li>
 *   <li>{@code requestId}——原始 AgentRequest.requestId——用于日志追踪</li>
 *   <li>{@code fullUserId}——用户上下文——Worker 做 RBAC 需要</li>
 *   <li>{@code payload}——TASK 时是 input（任务描述），RESULT 时是 output（答案）</li>
 *   <li>{@code error}——ERROR 时填错误信息</li>
 *   <li>{@code workerCostMs}——Worker 侧执行耗时（RESULT 时）</li>
 *   <li>{@code reflectionRounds} / {@code qualityScore}——反思元数据（RESULT 时）</li>
 *   <li>{@code metadata}——扩展字段</li>
 *   <li>{@code createdAt}——创建时间戳</li>
 * </ul>
 *
 * <h3>为什么 role 用 String 而非 AgentRole 枚举</h3>
 * <p>
 * 消息序列化到 JSON 时，枚举会变成字符串——反序列化时如果消息来自
 * 一个不认识的角色（如新版本 Worker 发的），会直接失败。
 * <p>
 * 用 String 保持宽容——消费侧用 {@code AgentRole.fromCode(...)} 解析，
 * 无法识别时降级为兜底处理，而不是拒绝整条消息。
 *
 * <h3>为什么 JSON 里所有字段都是可选的</h3>
 * <p>
 * 消息可能由不同版本的服务发出——加了新字段的版本发给旧版本时，
 * 旧版本不认识新字段（{@code @JsonIgnoreProperties}），
 * 旧版本发出的消息缺新字段（{@code AgentMessage} 的紧凑构造器兜底）。
 * <p>
 * 这是分布式消息的经典"版本兼容"问题——宽松策略比严格策略更稳。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentMessage(
        String messageId,
        String correlationId,
        MessageType type,
        String fromRole,
        String toRole,
        String requestId,
        String fullUserId,
        String payload,
        String error,
        long workerCostMs,
        int reflectionRounds,
        double qualityScore,
        Map<String, Object> metadata,
        long createdAt
) {

    public AgentMessage {
        if (messageId == null || messageId.isBlank()) {
            messageId = UUID.randomUUID().toString();
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = messageId;   // 兜底——至少能关联自己
        }
        if (type == null) {
            type = MessageType.TASK;
        }
        if (metadata == null) {
            metadata = Map.of();
        }
        if (createdAt <= 0) {
            createdAt = System.currentTimeMillis();
        }
        // 类型相关校验
        if (type == MessageType.TASK && (payload == null || payload.isBlank())) {
            throw new IllegalArgumentException("TASK 消息必须携带 payload（任务描述）");
        }
        if (type == MessageType.RESULT && (payload == null || payload.isBlank())) {
            throw new IllegalArgumentException("RESULT 消息必须携带 payload（答案）");
        }
        if (type == MessageType.ERROR && (error == null || error.isBlank())) {
            throw new IllegalArgumentException("ERROR 消息必须携带 error");
        }
    }

    // ==================== 静态工厂 ====================

    /**
     * 构造 TASK 消息——主管 → Worker（带 metadata）
     *
     * <h3>为什么需要这个重载</h3>
     * <p>
     * TASK 消息必须携带原始请求的 metadata——特别是 {@code securityLevel}。
     * 消费侧（{@code AgentMessageConsumer} → {@code WorkerAgent}）会用
     * 它做 RBAC 鉴权。
     * <p>
     * 把 metadata 直接作为工厂方法参数——比在调用侧用 {@code withMetadata()}
     * 逐个追加更直接，也不依赖 {@code AgentRequest} 的重载是否存在。
     *
     * @param metadata 消息 metadata（含 securityLevel 等上下文）——null 时用空 Map
     */
    public static AgentMessage task(String fromRole,
                                    String toRole,
                                    String requestId,
                                    String fullUserId,
                                    String input,
                                    String correlationId,
                                    Map<String, Object> metadata) {
        return new AgentMessage(
                null, correlationId, MessageType.TASK,
                fromRole, toRole, requestId, fullUserId,
                input, null,
                0L, 0, -1.0,
                metadata == null ? Map.of() : metadata, 0L);
    }

    /**
     * 构造 RESULT 消息——Worker → 主管
     */
    public static AgentMessage result(AgentMessage task,
                                      String fromRole,
                                      String output,
                                      long workerCostMs,
                                      int reflectionRounds,
                                      double qualityScore) {
        return new AgentMessage(
                null, task.correlationId(), MessageType.RESULT,
                fromRole, task.fromRole(), task.requestId(), task.fullUserId(),
                output, null,
                workerCostMs, reflectionRounds, qualityScore,
                Map.of(), 0L);
    }

    /**
     * 构造 ERROR 消息——Worker → 主管
     */
    public static AgentMessage error(AgentMessage task,
                                     String fromRole,
                                     String errorMsg,
                                     long workerCostMs) {
        return new AgentMessage(
                null, task.correlationId(), MessageType.ERROR,
                fromRole, task.fromRole(), task.requestId(), task.fullUserId(),
                null, errorMsg,
                workerCostMs, 0, -1.0,
                Map.of(), 0L);
    }

    // ==================== 派生 ====================

    /** 派生：追加 metadata */
    public AgentMessage withMetadata(String key, Object value) {
        Map<String, Object> newMeta = new HashMap<>(metadata);
        newMeta.put(key, value);
        return new AgentMessage(
                messageId, correlationId, type,
                fromRole, toRole, requestId, fullUserId,
                payload, error,
                workerCostMs, reflectionRounds, qualityScore,
                newMeta, createdAt);
    }

    /** 是否成功——RESULT 且无 error */
    public boolean isSuccess() {
        return type == MessageType.RESULT;
    }
}