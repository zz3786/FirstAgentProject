package org.example.multiagent.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.model.AgentMessage;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;
import org.example.multiagent.model.MessageType;
import org.example.multiagent.worker.WorkerAgent;
import org.example.multiagent.worker.WorkerAgentRegistry;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Agent 消息消费者（D74）
 *
 * <h3>核心流程</h3>
 * <pre>
 *   收到 MQ 消息
 *      ↓
 *   ① 反序列化为 AgentMessage
 *      ↓
 *   ② 幂等检查（Redis SETNX）
 *      ↓ 重复 → 丢弃
 *   ③ 解析 toRole
 *      ↓
 *   ④ 从 WorkerAgentRegistry 找 Worker
 *      ↓
 *   ⑤ 构造 AgentRequest → 调 worker.execute()
 *      ↓
 *   ⑥ 按结果发 RESULT/ERROR 消息
 * </pre>
 *
 * <h3>为什么消费者要单独成一个类</h3>
 * <p>
 * 让 {@code RabbitConsumerConfig}（配置层）只做"注册 listener container"，
 * 业务逻辑全部收敛到这里——便于单测、便于复用（未来接 Kafka 也用这个消费者）。
 *
 * <h3>异常策略</h3>
 * <p>
 * <ul>
 *   <li>反序列化失败 → 抛异常，让 MQ 走 DLX（进死信队列排查）</li>
 *   <li>幂等命中（重复） → 直接返回，正常 ack</li>
 *   <li>Worker 执行失败 → 捕获，发 ERROR 消息回执（不回滚）</li>
 *   <li>发回执失败 → 记录日志，抛异常走 DLX</li>
 * </ul>
 * <p>
 * <b>核心原则</b>：Worker 的业务失败（如 LLM 超时）不是"消息失败"——
 * 应该正常 ack + 发 ERROR 回执让调用方处理。
 * 只有"系统级失败"（反序列化错、发回执失败）才走 DLX。
 */
@Slf4j
@Component
public class AgentMessageConsumer {

    private final WorkerAgentRegistry workerRegistry;
    private final AgentMessageBus messageBus;
    private final MessageIdempotencyGuard idempotencyGuard;
    private final ObjectMapper objectMapper;

    public AgentMessageConsumer(WorkerAgentRegistry workerRegistry,
                                AgentMessageBus messageBus,
                                MessageIdempotencyGuard idempotencyGuard,
                                ObjectMapper objectMapper) {
        this.workerRegistry = workerRegistry;
        this.messageBus = messageBus;
        this.idempotencyGuard = idempotencyGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理 TASK 消息
     *
     * @param roleName 消费的角色名（yml 里的 key：financial / technical / legal）
     * @param amqpMessage RabbitMQ 原生消息
     */
    public void handleTask(String roleName, Message amqpMessage) {
        // ① 反序列化
        AgentMessage agentMessage = deserialize(amqpMessage);
        if (agentMessage == null) {
            log.error("[Consumer] 反序列化失败，走 DLX: role={}, body={}",
                    roleName, new String(amqpMessage.getBody(), StandardCharsets.UTF_8));
            throw new IllegalStateException("反序列化失败");  // → DLX
        }

        // ② 类型校验
        if (agentMessage.type() != MessageType.TASK) {
            log.warn("[Consumer] 非 TASK 消息被投递到 task queue: type={}, messageId={}",
                    agentMessage.type(), agentMessage.messageId());
            return;   // 忽略——正常 ack
        }

        // ③ 幂等检查
        if (!idempotencyGuard.tryMarkProcessed(agentMessage.messageId())) {
            return;   // 重复消息——正常 ack，跳过
        }

        log.info("[Consumer] 收到 TASK: role={}, messageId={}, correlationId={}, from={}",
                roleName, agentMessage.messageId(),
                agentMessage.correlationId(), agentMessage.fromRole());

        // ④ 执行
        AgentMessage response = executeTask(roleName, agentMessage);

        // ⑤ 发回执
        try {
            messageBus.publish(response);
            log.info("[Consumer] 已发回执: messageId={}, type={}, costMs={}",
                    response.messageId(), response.type(), response.workerCostMs());
        } catch (Exception e) {
            log.error("[Consumer] 发回执失败，走 DLX: messageId={}", response.messageId(), e);
            throw e;   // → DLX
        }
    }

    // ==================== 内部 ====================

    private AgentMessage executeTask(String roleName, AgentMessage task) {
        long start = System.currentTimeMillis();

        try {
            // 解析角色
            AgentRole role = AgentRole.fromCode(task.toRole());
            if (role == null) {
                role = AgentRole.fromCode(roleName.toUpperCase());
            }
            if (role == null) {
                return AgentMessage.error(task, roleName,
                        "无法解析角色: " + task.toRole(),
                        System.currentTimeMillis() - start);
            }

            // 找 Worker
            Optional<WorkerAgent> workerOpt = workerRegistry.get(role);
            if (workerOpt.isEmpty()) {
                return AgentMessage.error(task, role.name(),
                        "无可用 Worker: role=" + role,
                        System.currentTimeMillis() - start);
            }

            // 构造请求
            AgentRequest request = reconstructRequest(task, role);

            // 执行
            AgentResponse resp = workerOpt.get().execute(request);
            long cost = System.currentTimeMillis() - start;

            // 组装回执
            if (resp.success()) {
                return AgentMessage.result(task, role.name(),
                        resp.output(), cost,
                        resp.reflectionRounds(), resp.qualityScore());
            }
            return AgentMessage.error(task, role.name(), resp.error(), cost);

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[Consumer] 执行异常: messageId={}", task.messageId(), e);
            return AgentMessage.error(task, roleName,
                    "执行异常: " + e.getMessage(), cost);
        }
    }

    /**
     * 从 MQ 消息重建 AgentRequest
     * <p>
     * TASK 消息里的字段映射：
     * <ul>
     *   <li>{@code payload} → {@code input}</li>
     *   <li>{@code fullUserId} → {@code fullUserId}</li>
     *   <li>{@code requestId} → {@code requestId}</li>
     *   <li>{@code metadata} → {@code metadata}（含 securityLevel 等）</li>
     * </ul>
     */
    private AgentRequest reconstructRequest(AgentMessage task, AgentRole role) {
        return new AgentRequest(
                task.requestId(),
                role,
                task.payload(),
                null,   // conversationId——当前 D74 不带
                task.fullUserId(),
                null,   // parentTraceId——D77 引入
                task.metadata(),
                task.createdAt());
    }

    private AgentMessage deserialize(Message amqpMessage) {
        try {
            return objectMapper.readValue(amqpMessage.getBody(), AgentMessage.class);
        } catch (Exception e) {
            log.warn("[Consumer] 反序列化异常: {}", e.getMessage());
            return null;
        }
    }
}