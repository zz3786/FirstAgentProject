package org.example.multiagent;

import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;
import org.example.multiagent.model.SupervisorResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D72 主管 Agent 冒烟测试
 *
 * <h3>验证 3 件事</h3>
 * <ol>
 *   <li>拆解：LLM 能把复杂问题拆成多个独立子任务</li>
 *   <li>分发：Worker 被并行调用（D72 阶段用 GenericWorker 占位）</li>
 *   <li>汇总：多个 Worker 结果被整合成最终答案</li>
 * </ol>
 *
 * <h3>注意</h3>
 * <p>
 * 本测试会真实调用 LLM（拆解 + N×Worker + 汇总 = 至少 3 次 LLM 调用）。
 * 一次跑完可能耗时 30~60 秒。如果日志里能看到中间过程，
 * 说明流程正常，即使最终断言失败也值得先看日志。
 */
@SpringBootTest
class D72_SupervisorSmokeTest {

    @Autowired
    private MultiAgentFacade facade;

    @Test
    @DisplayName("D72-1：单视角问题——应拆出 1 个子任务")
    void testSingleSubtask() {
        AgentRequest req = AgentRequest.of(
                AgentRole.SUPERVISOR,
                "1+1 等于几？",
                "hospital-a:user-alice");

        AgentResponse resp = facade.execute(req);

        System.out.println("========== D72-1 结果 ==========");
        System.out.println("success = " + resp.success());
        System.out.println("output  = " + resp.output());
        SupervisorResult sr = (SupervisorResult) resp.metadata().get("supervisorResult");
        if (sr != null) {
            System.out.println("goal         = " + sr.taskGraph().goal());
            System.out.println("subtasks     = " + sr.taskGraph().size());
            System.out.println("workerCount  = " + sr.workerResponses().size());
            System.out.println("successRate  = " + sr.workerSuccessRate());
        }
        System.out.println("costMs  = " + resp.costMs());
        System.out.println("================================");

        assertTrue(resp.success(), "应成功");
        assertNotNull(sr, "应包含 supervisorResult");
        assertTrue(sr.taskGraph().size() >= 1, "至少 1 个子任务");
    }

    @Test
    @DisplayName("D72-2：多视角问题——应拆出多个子任务，触发并行")
    void testMultiSubtasks() {
        AgentRequest req = AgentRequest.of(
                AgentRole.SUPERVISOR,
                "我们公司要上一个客户管理系统，请从财务、技术、法务三个角度评估可行性",
                "hospital-a:user-alice");

        AgentResponse resp = facade.execute(req);

        System.out.println("========== D72-2 结果 ==========");
        System.out.println("success = " + resp.success());
        System.out.println("output  = " + resp.output());
        SupervisorResult sr = (SupervisorResult) resp.metadata().get("supervisorResult");
        if (sr != null) {
            System.out.println("goal         = " + sr.taskGraph().goal());
            System.out.println("subtasks     = " + sr.taskGraph().size());
            sr.taskGraph().subtasks().forEach(st ->
                    System.out.println("  [" + st.id() + "] " + st.role() + " — " + st.description()));
            System.out.println("workerCount  = " + sr.workerResponses().size());
            System.out.println("successRate  = " + sr.workerSuccessRate());
        }
        System.out.println("costMs  = " + resp.costMs());
        System.out.println("================================");

        assertTrue(resp.success(), "应成功");
        assertNotNull(sr, "应包含 supervisorResult");
        // 多视角问题——期望至少 2 个子任务
        // 但不做硬断言——LLM 可能合并为一个（用户描述不够明确时）
        System.out.println("★ 观察点：子任务数=" + sr.taskGraph().size() + "（期望 3）");
    }

    @Test
    @DisplayName("D72-3：串行问题——应降级为单子任务（不属于 Supervisor 场景）")
    void testSerialDecomposedAsSingle() {
        AgentRequest req = AgentRequest.of(
                AgentRole.SUPERVISOR,
                "先查询订单 1001 的状态，如果已发货就通知我，否则就不动",
                "hospital-a:user-alice");

        AgentResponse resp = facade.execute(req);

        System.out.println("========== D72-3 结果 ==========");
        System.out.println("success = " + resp.success());
        SupervisorResult sr = (SupervisorResult) resp.metadata().get("supervisorResult");
        if (sr != null) {
            System.out.println("goal         = " + sr.taskGraph().goal());
            System.out.println("subtasks     = " + sr.taskGraph().size());
            sr.taskGraph().subtasks().forEach(st ->
                    System.out.println("  [" + st.id() + "] " + st.role() + " — " + st.description()));
        }
        System.out.println("================================");

        assertTrue(resp.success(), "应成功");
        // 期望拆 1 个子任务（Prompt 里有反例引导）
        // 但不做硬断言——LLM 可能拆 2 个
        System.out.println("★ 观察点：子任务数=" + sr.taskGraph().size() + "（期望 1）");
    }
}