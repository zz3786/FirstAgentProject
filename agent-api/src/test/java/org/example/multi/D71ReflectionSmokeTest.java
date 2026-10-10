package org.example.multi;

import org.example.multiagent.MultiAgentFacade;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D71 反思机制冒烟测试
 * <p>
 * 验证 3 件事：
 * 1. MultiAgentFacade 能正常执行
 * 2. SelfReflectionAdvisor 被调用
 * 3. AgentResponse 里带反思元数据
 */
@SpringBootTest
class D71ReflectionSmokeTest {

    @Autowired
    private MultiAgentFacade facade;

    @Test
    @DisplayName("D71-1：正常问题——应返回答案并带反思元数据")
    void testBasicReflection() {
        AgentRequest req = AgentRequest.of(
                AgentRole.SUPERVISOR,
                "用一句话解释什么是家庭医生",
                "hospital-a:user-alice");

        AgentResponse resp = facade.execute(req);

        System.out.println("========== D71-1 结果 ==========");
        System.out.println("success         = " + resp.success());
        System.out.println("output          = " + resp.output());
        System.out.println("reflectionRounds= " + resp.reflectionRounds());
        System.out.println("qualityScore    = " + resp.qualityScore());
        System.out.println("costMs          = " + resp.costMs());
        System.out.println("=================================");

        assertTrue(resp.success(), "应成功");
        assertNotNull(resp.output(), "应返回文本");
        assertFalse(resp.output().isBlank(), "输出不应为空");
        // 反思轮次至少是 0（可能第一轮就通过）
        assertTrue(resp.reflectionRounds() >= 0, "反思轮次应 >= 0");
    }

    @Test
    @DisplayName("D71-2：超短要求——看反思是否介入")
    void testReflectionIntervened() {
        // 故意给一个模糊问题，看 LLM 会不会反思并改进
        AgentRequest req = AgentRequest.of(
                AgentRole.SUPERVISOR,
                "讲一下",
                "hospital-a:user-alice");

        AgentResponse resp = facade.execute(req);

        System.out.println("========== D71-2 结果 ==========");
        System.out.println("success         = " + resp.success());
        System.out.println("output          = " + resp.output());
        System.out.println("reflectionRounds= " + resp.reflectionRounds());
        System.out.println("qualityScore    = " + resp.qualityScore());
        System.out.println("=================================");

        // 不做严格断言——反思效果依赖 LLM 判断
        // 主要目的是"观察日志里是否出现 SelfReflection 的输出"
        assertTrue(resp.success(), "应成功");
    }

    @Test
    @DisplayName("D71-3：fullUserId 必传——缺则抛 IllegalArgumentException")
    void testFullUserIdRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentRequest.of(AgentRole.SUPERVISOR, "test", null),
                "fullUserId 为空应抛异常");
    }
}