package org.example.multiagent.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.model.AgentMessage;
import org.example.multiagent.model.MessageType;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 结果回执监听器（D74 修复）
 *
 * <h3>它解决什么问题</h3>
 * <p>
 * 首版遗漏了"主管侧接收回执"这一环——Worker 处理完发 RESULT 消息，
 * 但没人监听 `agent.supervisor.result.q`，Future 永远不完成，超时。
 *
 * <h3>职责</h3>
 * <p>
 * 本类与 {@code AgentMessageConsumer} 职责不同：
 * <ul>
 *   <li>{@code AgentMessageConsumer}——处理 TASK 消息（消费 → 调 Worker → 发 RESULT）</li>
 *   <li>{@code ResultCorrelationListener}——处理 RESULT/ERROR 消息（完成 Future）</li>
 * </ul>
 * <p>
 * <b>为什么不复用 AgentMessageConsumer</b>：
 * <ul>
 *   <li>语义不同——TASK 要"执行业务"，RESULT 只要"完成等待"</li>
 *   <li>失败处理不同——TASK 失败要发 ERROR 回执，RESULT 失败只记日志</li>
 *   <li>合在一起会让"一个 if 分支决定走哪条逻辑"——不符合单一职责</li>
 * </ul>
 *
 * <h3>幂等性</h3>
 * <p>
 * 回执消息不需要幂等——因为：
 * <ul>
 *   <li>Future 完成是幂等的——重复 complete 返回 false，不报错</li>
 *   <li>correlationId 由主管侧生成——重复回执完成同一个 Future 影响不大</li>
 * </ul>
 * <p>
 * <b>注意</b>：这依赖"correlationId 是全局唯一"——未来若改用"临时 reply queue"
 * 模式，可能需要调整。
 */
@Slf4j
@Component
public class ResultCorrelationListener {

    private final MessageCorrelationRegistry correlationRegistry;
    private final ObjectMapper objectMapper;

    public ResultCorrelationListener(MessageCorrelationRegistry correlationRegistry,
                                     ObjectMapper objectMapper) {
        this.correlationRegistry = correlationRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理一条回执消息
     *
     * @param roleName     消费的角色名（yml 里的 key）——仅用于日志
     * @param amqpMessage  RabbitMQ 原生消息
     */
    public void handleResult(String roleName, Message amqpMessage) {
        AgentMessage message = deserialize(amqpMessage);
        if (message == null) {
            // 反序列化失败——记日志，ack 掉（不 requeue——DLX 已经存了一份）
            log.error("[ResultListener] 反序列化失败: role={}, body={}",
                    roleName, new String(amqpMessage.getBody(), StandardCharsets.UTF_8));
            return;
        }

        // 类型校验——result queue 只该收 RESULT / ERROR
        if (message.type() != MessageType.RESULT && message.type() != MessageType.ERROR) {
            log.warn("[ResultListener] 非 RESULT/ERROR 消息被投递到 result queue: " +
                            "type={}, messageId={}, 忽略",
                    message.type(), message.messageId());
            return;
        }

        String correlationId = message.correlationId();
        if (correlationId == null || correlationId.isBlank()) {
            log.warn("[ResultListener] 消息无 correlationId，无法关联: messageId={}",
                    message.messageId());
            return;
        }

        log.info("[ResultListener] 收到回执: type={}, correlationId={}, from={}, costMs={}",
                message.type(), correlationId,
                message.fromRole(), message.workerCostMs());

        // 完成对应 Future
        boolean completed = correlationRegistry.complete(correlationId, message);
        if (!completed) {
            // 正常情况——可能主管侧已超时/取消
            log.warn("[ResultListener] 无对应等待（可能已超时）: correlationId={}, " +
                            "pendingCount={}",
                    correlationId, correlationRegistry.pendingCount());
        }
    }

    private AgentMessage deserialize(Message amqpMessage) {
        try {
            return objectMapper.readValue(amqpMessage.getBody(), AgentMessage.class);
        } catch (Exception e) {
            log.warn("[ResultListener] 反序列化异常: {}", e.getMessage());
            return null;
        }
    }
}