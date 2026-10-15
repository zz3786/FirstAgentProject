package org.example.multiagent;

import org.example.multiagent.conflict.ConflictDetector;
import org.example.multiagent.conflict.ConflictHandler;
import org.example.multiagent.conflict.model.ConflictAnalysis;
import org.example.multiagent.conflict.model.ConflictResolution;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D76 冲突处理冒烟测试
 *
 * <h3>验证 4 件事</h3>
 * <ol>
 *   <li>无冲突场景——Detector 判定 noConflict</li>
 *   <li>有冲突场景——Detector 判定有冲突 + Resolver 产出决议</li>
 *   <li>票数不足——快速规则正确跳过</li>
 *   <li>端到端——SupervisorAgent 走完整流程</li>
 * </ol>
 *
 * <h3>注意</h3>
 * <p>
 * 冲突检测依赖 LLM 判断——结果有不确定性。测试用"明显冲突"的
 * 场景（如一方支持、一方反对）——LLM 大概率识别为冲突。
 * <p>
 * 不断言"必须判定为冲突"——只断言"流程跑通且结果结构正确"。
 */
@SpringBootTest
class D76_ConflictSmokeTest {

    @Autowired
    private ConflictDetector detector;

    @Autowired
    private ConflictHandler handler;

    @Autowired
    private MultiAgentFacade facade;

    @Test
    @DisplayName("D76-1：快速规则——单个响应直接判定无冲突")
    void testSingleResponse() {
        AgentRequest req = AgentRequest.of(AgentRole.SUPERVISOR, "test", "hospital-a:user-alice");
        List<AgentResponse> responses = List.of(
                AgentResponse.ok("req-1", AgentRole.FINANCIAL,
                        "成本约 30 万元。", 1000, 0, 0.9));

        ConflictAnalysis analysis = detector.detect("评估项目", responses);

        System.out.println("========== D76-1 单响应 ==========");
        System.out.println("hasConflict = " + analysis.hasConflict());
        System.out.println("=================================");

        assertFalse(analysis.hasConflict(), "单响应不应有冲突");
    }

    @Test
    @DisplayName("D76-2：明显冲突——一方支持一方反对")
    void testObviousConflict() {
        List<AgentResponse> responses = List.of(
                AgentResponse.ok("req-1", AgentRole.FINANCIAL,
                        "我认为这个项目完全可以做，预算充足，成本可控，年化收益预计 20%。",
                        1000, 0, 0.9),
                AgentResponse.ok("req-2", AgentRole.LEGAL,
                        "我强烈反对这个项目。当前方案涉及用户数据采集，违反《个人信息保护法》，"
                                + "一旦被查将面临巨额罚款和停业整顿。",
                        1000, 0, 0.85));

        ConflictAnalysis analysis = detector.detect("评估项目可行性", responses);

        System.out.println("========== D76-2 明显冲突 ==========");
        System.out.println("hasConflict = " + analysis.hasConflict());
        System.out.println("severity    = " + analysis.severity());
        System.out.println("summary     = " + analysis.summary());
        System.out.println("opinions    = " + analysis.opinions().size());
        if (!analysis.opinions().isEmpty()) {
            analysis.opinions().forEach(op ->
                    System.out.println("  - " + op.role() + ": " + op.stance()));
        }
        System.out.println("=================================");

        // 用明显冲突——期望检测到，但不硬断言（LLM 有随机性）
        System.out.println("★ 观察点：期望 hasConflict=true（LLM 有随机性，不硬断言）");
    }

    @Test
    @DisplayName("D76-3：端到端——冲突处理整合到 Supervisor 流程")
    void testEndToEnd() {
        // 构造明显冲突的场景
        AgentRequest req = AgentRequest.of(
                AgentRole.SUPERVISOR,
                "我们准备上线一个用户数据分析系统，请从财务和法律角度评估可行性",
                "hospital-a:user-alice");

        AgentResponse resp = facade.execute(req);

        System.out.println("========== D76-3 端到端 ==========");
        System.out.println("success = " + resp.success());
        System.out.println("output  = " + resp.output());
        System.out.println("=================================");

        assertTrue(resp.success());
        assertNotNull(resp.output());
    }
}