package org.example.multiagent.bus;

import org.example.multiagent.model.*;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Agent 消息与业务模型的转换器（D74 融合）
 *
 * <h3>职责</h3>
 * <p>
 * MQ 层用 {@link AgentMessage}（扁平、JSON 友好），业务层用
 * {@link AgentRequest} / {@link AgentResponse}（强类型、含 record 语义）。
 * 本类负责两层之间的映射——集中处理，避免转换逻辑散落各处。
 *
 * <h3>为什么独立成类</h3>
 * <ul>
 *   <li>映射逻辑复杂（field 对应、默认值、metadata 传递）——集中一处便于维护</li>
 *   <li>测试友好——单测只测转换，不涉及 MQ</li>
 *   <li>未来换消息格式（如 Avro）只改本类</li>
 * </ul>
 *
 * <h3>关键字段映射</h3>
 * <pre>
 *   AgentRequest                   AgentMessage (TASK)
 *   ─────────────                  ──────────────────
 *   requestId                      requestId
 *   role                           toRole
 *   input                          payload
 *   fullUserId                     fullUserId
 *   metadata                       metadata   ← securityLevel 等上下文
 *   （固定）                        fromRole = "SUPERVISOR"
 *
 *   AgentMessage (RESULT/ERROR)    AgentResponse
 *   ────────────────────────       ─────────────
 *   requestId                      requestId
 *   fromRole                       role
 *   payload                        output（RESULT 时）
 *   error                          error（ERROR 时）
 *   workerCostMs                   costMs
 *   reflectionRounds               reflectionRounds
 *   qualityScore                   qualityScore
 * </pre>
 */
@Component
public class AgentMessageConverter {

    /**
     * AgentRequest → TASK 消息
     *
     * <h3>correlationId 为什么用 UUID</h3>
     * <p>
     * 首版用 {@code "mq-{requestId}-{currentTimeMillis}"} 生成——
     * 存在两个碰撞源：
     * <ul>
     *   <li>并发发送的多个子任务<b>共享同一个 parentRequest.requestId</b></li>
     *   <li>虚拟线程并行执行——{@code System.currentTimeMillis()} 可能落在同一毫秒</li>
     * </ul>
     * 两者叠加 → correlationId 碰撞 → {@code MessageCorrelationRegistry} 抛异常。
     * <p>
     * 改用 UUID——全局唯一，从源头消除碰撞。
     *
     * <h3>为什么不用 subtaskId</h3>
     * <p>
     * subtaskId 只是"同一次请求内的序号"——跨请求（如用户连发两次相同问题）
     * 还是可能重复。UUID 才能真正保证唯一。
     *
     * @param request 业务请求
     * @param fromRole 发送方角色名（字符串）
     * @return TASK 消息
     */
    public AgentMessage toTaskMessage(AgentRequest request, String fromRole) {
        // ★ 修复：correlationId 用 UUID 保证全局唯一
        String correlationId = "mq-" + UUID.randomUUID();

        return AgentMessage.task(
                fromRole,
                request.role().name(),
                request.requestId(),
                request.fullUserId(),
                request.input(),
                correlationId,
                request.metadata());
    }

    /**
     * RESULT/ERROR 消息 → AgentResponse
     *
     * @param message 回执消息
     * @return 业务响应
     * @throws IllegalArgumentException message 类型不是 RESULT/ERROR
     */
    public AgentResponse toAgentResponse(AgentMessage message) {
        if (message.type() == MessageType.TASK) {
            throw new IllegalArgumentException(
                    "TASK 消息不能转换为 AgentResponse: messageId=" + message.messageId());
        }

        AgentRole role = AgentRole.fromCode(message.fromRole());
        if (role == null) {
            role = AgentRole.SUPERVISOR;   // 兜底——不该发生
        }

        if (message.type() == MessageType.RESULT) {
            return AgentResponse.ok(
                    message.requestId(),
                    role,
                    message.payload(),
                    message.workerCostMs(),
                    message.reflectionRounds(),
                    message.qualityScore(),
                    message.metadata());
        }

        // ERROR
        return AgentResponse.fail(
                message.requestId(),
                role,
                message.error(),
                message.workerCostMs(),
                message.metadata());
    }
}