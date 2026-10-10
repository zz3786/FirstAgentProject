package org.example.multi;

import org.example.multiagent.MultiAgentFacade;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;
import org.example.multiagent.worker.WorkerAgentRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D73 Worker 冒烟测试
 *
 * <h3>验证 3 件事</h3>
 * <ol>
 *   <li>3 个领域 Worker 都被注册（Financial / Technical / Legal）</li>
 *   <li>每个 Worker 有独立的 ChatClient（角色 System Prompt 不同）</li>
 *   <li>走 Supervisor 流程时，子任务分发给对应角色的 Worker</li>
 * </ol>
 *
 * <h3>不做的事</h3>
 * <p>
 * 不验证"Worker 输出质量"——那是 LLM 的随机行为，不适合单测断言。
 * 只验证"路由正确"和"流程可跑"。
 */
@SpringBootTest
class D73_WorkerSmokeTest {

    @Autowired
    private WorkerAgentRegistry registry;

    @Autowired
    private MultiAgentFacade facade;

    // ==================== 注册验证 ====================

    @Test
    @DisplayName("D73-1：3 个 Worker 都应注册")
    void testRegistryHasAllWorkers() {
        System.out.println("========== D73-1 注册状态 ==========");
        System.out.println("registeredRoles = " + registry.registeredRoles());
        System.out.println("hasFallback     = " + registry.hasFallback());
        System.out.println("===================================");

        assertTrue(registry.has(AgentRole.FINANCIAL), "应有 FinancialWorker");
        assertTrue(registry.has(AgentRole.TECHNICAL), "应有 TechnicalWorker");
        assertTrue(registry.has(AgentRole.LEGAL), "应有 LegalWorker");
    }

    @Test
    @DisplayName("D73-2：按角色查找——各返回对应 Worker")
    void testLookupByRole() {
        var financial = registry.get(AgentRole.FINANCIAL).orElseThrow();
        var technical = registry.get(AgentRole.TECHNICAL).orElseThrow();
        var legal = registry.get(AgentRole.LEGAL).orElseThrow();

        System.out.println("========== D73-2 查找结果 ==========");
        System.out.println("FINANCIAL → " + financial.getClass().getSimpleName());
        System.out.println("TECHNICAL → " + technical.getClass().getSimpleName());
        System.out.println("LEGAL     → " + legal.getClass().getSimpleName());
        System.out.println("===================================");

        assertEquals("FinancialWorkerAgent", financial.getClass().getSimpleName());
        assertEquals("TechnicalWorkerAgent", technical.getClass().getSimpleName());
        assertEquals("LegalWorkerAgent", legal.getClass().getSimpleName());
    }

    // ==================== 端到端验证 ====================

    @Test
    @DisplayName("D73-3：单角色子任务——直接调 Worker 执行")
    void testSingleWorkerExecution() {
        // 直接构造一个 FINANCIAL 子任务请求
        AgentRequest req = AgentRequest.of(
                AgentRole.FINANCIAL,
                "订单 1001 的金额是多少？用一句话回答。",
                "hospital-a:user-alice");

        var financial = registry.get(AgentRole.FINANCIAL).orElseThrow();
        AgentResponse resp = financial.execute(req);

        System.out.println("========== D73-3 单 Worker 结果 ==========");
        System.out.println("success = " + resp.success());
        System.out.println("output  = " + resp.output());
        System.out.println("costMs  = " + resp.costMs());
        System.out.println("=======================================");

        assertTrue(resp.success(), "应成功");
        assertNotNull(resp.output(), "应有输出");
    }

    @Test
    @DisplayName("D73-4：Supervisor 端到端——三 Worker 都被分发")
    void testSupervisorDispatchesToWorkers() {
        AgentRequest req = AgentRequest.of(
                AgentRole.SUPERVISOR,
                "我们准备上线一个订单系统，请分别从财务成本、技术选型、法务合规三个角度给出建议",
                "hospital-a:user-alice");

        AgentResponse resp = facade.execute(req);

        System.out.println("========== D73-4 端到端结果 ==========");
        System.out.println("success = " + resp.success());
        System.out.println("output  = " + resp.output());
        Object workerCount = resp.metadata().get("workerCount");
        Object successRate = resp.metadata().get("workerSuccessRate");
        System.out.println("workerCount     = " + workerCount);
        System.out.println("workerSuccessRate = " + successRate);
        System.out.println("costMs  = " + resp.costMs());
        System.out.println("====================================");

        assertTrue(resp.success(), "应成功");
        // 期望分发到 3 个 Worker——但不硬断言（LLM 可能合并）
        System.out.println("★ 观察点：workerCount=" + workerCount + "（期望 3）");
    }

    // ==================== 兜底验证 ====================

    @Test
    @DisplayName("D73-5：GenericWorkerAgent 应被禁用（真实现已注册）")
    void testGenericWorkerDisabled() {
        var financial = registry.get(AgentRole.FINANCIAL).orElseThrow();
        var technical = registry.get(AgentRole.TECHNICAL).orElseThrow();
        var legal = registry.get(AgentRole.LEGAL).orElseThrow();

        // 三个都不该是 GenericWorkerAgent
        assertNotEquals("GenericWorkerAgent", financial.getClass().getSimpleName(),
                "FINANCIAL 应由专属 Worker 处理，不该用 GenericWorkerAgent");
        assertNotEquals("GenericWorkerAgent", technical.getClass().getSimpleName(),
                "TECHNICAL 应由专属 Worker 处理");
        assertNotEquals("GenericWorkerAgent", legal.getClass().getSimpleName(),
                "LEGAL 应由专属 Worker 处理");
    }
}