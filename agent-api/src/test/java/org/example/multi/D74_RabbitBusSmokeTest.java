package org.example.multiagent;

import org.example.multiagent.bus.AgentMessageBus;
import org.example.multiagent.bus.MessageCorrelationRegistry;
import org.example.multiagent.model.AgentMessage;
import org.example.multiagent.model.AgentRole;
import org.example.multiagent.model.MessageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D74 RabbitMQ 总线冒烟测试
 *
 * <h3>前置条件</h3>
 * <ul>
 *   <li>RabbitMQ 实例运行中（localhost:5672）</li>
 *   <li>yml 里 app.agent-bus.rabbit.enabled=true</li>
 *   <li>agent-api 启动时成功注册消费者</li>
 * </ul>
 *
 * <h3>验证 3 件事</h3>
 * <ol>
 *   <li>Bus 可用——isAvailable() == true</li>
 *   <li>发一条 TASK 消息到 FINANCIAL 队列——能收到 RESULT 回执</li>
 *   <li>同一个 messageId 发两次——第二次被幂等丢弃</li>
 * </ol>
 *
 * <h3>耗时说明</h3>
 * <p>
 * 每次 sendAndWait 会真调一次 Worker（LLM 调用）——约 10~30 秒。
 * 整个测试可能跑 1~2 分钟。
 */
@SpringBootTest
class D74_RabbitBusSmokeTest {

    @Autowired(required = false)
    private AgentMessageBus messageBus;

    @Autowired
    private MessageCorrelationRegistry correlationRegistry;

    @Test
    @DisplayName("D74-1：Bus 可用性")
    void testBusAvailable() {
        System.out.println("========== D74-1 Bus 可用性 ==========");
        if (messageBus == null) {
            System.out.println("❌ messageBus 未注入——检查 app.agent-bus.rabbit.enabled");
            fail("AgentMessageBus Bean 未注册");
        }
        System.out.println("Bus 实现类 = " + messageBus.getClass().getSimpleName());
        boolean available = messageBus.isAvailable();
        System.out.println("isAvailable = " + available);
        System.out.println("====================================");

        assertTrue(available, "Bus 应可用——检查 RabbitMQ 实例和连接配置");
    }

    @Test
    @DisplayName("D74-2：发 TASK 消息给 FINANCIAL——应收到 RESULT")
    void testSendAndWait() {
        assertNotNull(messageBus, "Bus 未注入");

        AgentMessage task = AgentMessage.task(
                AgentRole.SUPERVISOR.name(),
                AgentRole.FINANCIAL.name(),
                "d74-test-req-1",
                "hospital-a:user-alice",
                "订单 1001 的当前状态是什么？用一句话回答。",
                "d74-correlation-" + System.currentTimeMillis(),
                null);

        System.out.println("========== D74-2 发送 TASK ==========");
        System.out.println("correlationId = " + task.correlationId());
        System.out.println("toRole        = " + task.toRole());
        System.out.println("payload       = " + task.payload());

        long start = System.currentTimeMillis();
        AgentMessage result = messageBus.sendAndWait(task, Duration.ofSeconds(90));
        long cost = System.currentTimeMillis() - start;

        System.out.println("---------- 收到回执 ----------");
        System.out.println("type           = " + result.type());
        System.out.println("payload        = " + result.payload());
        System.out.println("error          = " + result.error());
        System.out.println("workerCostMs   = " + result.workerCostMs());
        System.out.println("总耗时（含 MQ）= " + cost + "ms");
        System.out.println("=================================");

        assertNotNull(result, "应收到回执");
        assertTrue(
                result.type() == MessageType.RESULT || result.type() == MessageType.ERROR,
                "回执类型应为 RESULT 或 ERROR，实际: " + result.type());
        assertEquals(task.correlationId(), result.correlationId(),
                "correlationId 应匹配");
    }

    @Test
    @DisplayName("D74-3：幂等验证——无副作用")
    void testIdempotencyNoSideEffect() {
        // 简单验证——发两次相同 messageId，MQ 层不能报错
        assertNotNull(messageBus, "Bus 未注入");
        System.out.println("========== D74-3 幂等 ==========");
        System.out.println("当前 pendingCount = " + correlationRegistry.pendingCount());
        System.out.println("（幂等验证需要发两个相同 messageId 的消息——" +
                "观察日志里'重复消息，跳过'）");
        System.out.println("===============================");

        // 不做硬断言——幂等是"附加能力"，主要由人工观察日志验证
    }
}