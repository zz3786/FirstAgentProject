package org.example.multiagent;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D77 链路追踪冒烟测试
 *
 * <h3>验证 4 件事</h3>
 * <ol>
 *   <li>Tracer Bean 存在——tracing 基础设施就绪</li>
 *   <li>能创建手工 Span——Tracer API 可用</li>
 *   <li>ChatClient 调用会生成 Span</li>
 *   <li>工具调用会生成 Span</li>
 * </ol>
 *
 * <h3>前置条件</h3>
 * <ul>
 *   <li>Zipkin 服务运行中（localhost:9411）</li>
 *   <li>{@code management.tracing.sampling.probability >= 1.0}（dev profile）</li>
 * </ul>
 */
@SpringBootTest
class D77_TracingSmokeTest {

    @Autowired(required = false)
    private Tracer tracer;

    @Autowired
    private MultiAgentFacade facade;

    @Test
    @DisplayName("D77-1：Tracer Bean 存在")
    void testTracerAvailable() {
        System.out.println("========== D77-1 Tracer ==========");
        if (tracer == null) {
            System.out.println("❌ Tracer 未注入");
            fail("Tracer 未注入——检查 agent-observability 依赖和配置");
        }
        System.out.println("Tracer 实现 = " + tracer.getClass().getName());
        System.out.println("=================================");
        assertNotNull(tracer);
    }

    @Test
    @DisplayName("D77-2：手工创建 Span")
    void testManualSpan() throws InterruptedException {
        assertNotNull(tracer, "Tracer 未注入");

        System.out.println("========== D77-2 诊断 ==========");
        System.out.println("Tracer 实现类 = " + tracer.getClass().getName());

        Span span = tracer.nextSpan().name("test.manual").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            System.out.println("Span 实现类   = " + span.getClass().getName());
            System.out.println("spanId        = " + span.context().spanId());
            System.out.println("traceId       = " + span.context().traceId());

            // ★ 修正：采样状态在 context 上
            System.out.println("sampled       = " + span.context().sampled());
            System.out.println("Context 实现  = " + span.context().getClass().getName());

            System.out.println("currentSpan   = " + tracer.currentSpan());
            Thread.sleep(50);
        } finally {
            span.end();
        }

        System.out.println("★ 等待 10 秒让 reporter 上报...");
        Thread.sleep(10_000);
        System.out.println("★ 完成");
    }

    @Test
    @DisplayName("D77-3：ChatClient 调用生成 Span（端到端）")
    void testChatClientSpan() {
        assertNotNull(tracer, "Tracer 未注入");

        AgentRequest req = AgentRequest.of(
                AgentRole.SUPERVISOR,
                "用一句话回答：1+1 等于几？",
                "hospital-a:user-alice");

        AgentResponse resp = facade.execute(req);

        System.out.println("========== D77-3 ChatClient Span ==========");
        System.out.println("success = " + resp.success());
        System.out.println("output  = " + resp.output());
        System.out.println("==========================================");

        assertTrue(resp.success());
        System.out.println("★ 打开 Zipkin 应能看到 chat.client / chat.client.stream Span");
        System.out.println("★ 或 agent.supervisor / agent.worker.* Span");
    }

    @Test
    @DisplayName("D77-4：工具调用生成 Span")
    void testToolSpan() {
        assertNotNull(tracer, "Tracer 未注入");

        AgentRequest req = AgentRequest.of(
                AgentRole.FINANCIAL,
                "帮我算 123 加 456 等于多少？",
                "hospital-a:user-alice");

        // 直接走 Worker（会触发工具调用）
        var resp = facade.execute(req);

        System.out.println("========== D77-4 工具 Span ==========");
        System.out.println("success = " + resp.success());
        System.out.println("output  = " + resp.output());
        System.out.println("====================================");

        System.out.println("★ Zipkin 里应能看到 tool.invoke.calculate Span");
    }
}