package org.example.multiagent;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.config.MultiAgentProperties;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;
import org.example.multiagent.model.SupervisorResult;
import org.example.multiagent.reflection.ReflectionMetadata;
import org.example.multiagent.supervisor.SupervisorAgent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 多智能体门面（D71 新建，D72 扩展）
 *
 * <h3>D72 改动</h3>
 * <p>
 * 增加 role 路由：
 * <ul>
 *   <li>{@code role == SUPERVISOR} → 走 {@link SupervisorAgent}（拆解-分发-汇总）</li>
 *   <li>其他 role → 走 D71 的单 Agent + 反思流程</li>
 * </ul>
 *
 * <h3>为什么用 role 而不是新增方法</h3>
 * <p>
 * 对调用方来说"我要执行一个 Agent 请求"是统一语义——
 * 内部的"是否走主管模式"应该由请求里的 role 决定，而不是让调用方
 * 纠结"我该调哪个方法"。
 * <p>
 * 这符合"<b>单一入口 + 参数路由</b>"的设计——Controller 只认识一个 execute。
 *
 * <h3>D73+ 会怎么演进</h3>
 * <ul>
 *   <li>D73：worker 实现变多——但这不影响 Facade，因为 Facade 只管 SUPERVISOR 和其他角色的分派</li>
 *   <li>D74：execute 可能变成"提交异步任务"——返回 executionId 而不是结果</li>
 *   <li>D75：聚合逻辑抽到 Aggregator，Facade 调用它</li>
 * </ul>
 */
@Slf4j
@Service
public class MultiAgentFacade {

    private final ChatClient reflectiveChatClient;
    private final SupervisorAgent supervisorAgent;
    private final MultiAgentProperties props;

    public MultiAgentFacade(
            @Qualifier("reflectiveChatClient") ChatClient reflectiveChatClient,
            SupervisorAgent supervisorAgent,
            MultiAgentProperties props) {
        this.reflectiveChatClient = reflectiveChatClient;
        this.supervisorAgent = supervisorAgent;
        this.props = props;
    }

    /**
     * 执行一次 Agent 调用——按 role 路由
     *
     * <h3>路由规则</h3>
     * <ul>
     *   <li>{@code SUPERVISOR} → SupervisorAgent 拆解分发</li>
     *   <li>其他角色（FINANCIAL / TECHNICAL / LEGAL） → 单 Agent 直调</li>
     * </ul>
     *
     * <h3>返回类型</h3>
     * <p>
     * 统一返回 {@link AgentResponse}——Supervisor 结果里的最终答案会被
     * 提取到 {@code output} 字段。<b>完整的 SupervisorResult</b>（含任务图、
     * 所有 Worker 响应）通过 {@code metadata["supervisorResult"]} 传递——
     * 需要的调用方（如 debug 接口）可以取出来。
     */
    public AgentResponse execute(AgentRequest request) {
        long start = System.currentTimeMillis();

        // ① 开关校验
        if (!props.isEnabled()) {
            log.warn("[MultiAgent] 总开关关闭，拒绝执行: role={}, requestId={}",
                    request.role(), request.requestId());
            return AgentResponse.fail(
                    request.requestId(), request.role(),
                    "多智能体协作未启用（app.multi-agent.enabled=false）", 0);
        }

        // ② 按 role 路由
        if (request.role() == AgentRole.SUPERVISOR) {
            return executeSupervisor(request, start);
        }
        return executeSingle(request, start);
    }

    // ==================== 主管模式 ====================

    private AgentResponse executeSupervisor(AgentRequest request, long start) {
        try {
            SupervisorResult result = supervisorAgent.execute(request);

            long cost = System.currentTimeMillis() - start;

            // 把 SupervisorResult 塞进 metadata——供上层按需读取
            AgentResponse resp = AgentResponse.ok(
                            request.requestId(),
                            AgentRole.SUPERVISOR,
                            result.finalAnswer(),
                            cost,
                            0,
                            -1.0)
                    .withMetadata("supervisorResult", result)
                    .withMetadata("workerCount", result.workerResponses().size())
                    .withMetadata("workerSuccessRate", result.workerSuccessRate());

            log.info("[MultiAgent] 主管模式完成: requestId={}, 子任务={}, 成功率={}, costMs={}",
                    request.requestId(),
                    result.taskGraph().size(),
                    String.format("%.2f", result.workerSuccessRate()),
                    cost);

            return resp;

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[MultiAgent] 主管模式失败: requestId={}", request.requestId(), e);
            return AgentResponse.fail(
                    request.requestId(), request.role(),
                    "主管流程失败: " + e.getMessage(), cost);
        }
    }

    // ==================== 单 Agent 模式（D71 老路） ====================

    private AgentResponse executeSingle(AgentRequest request, long start) {
        try {
            ChatClientResponse response = reflectiveChatClient.prompt()
                    .user(request.input())
                    .call()
                    .chatClientResponse();

            String finalAnswer = extractFinalAnswer(response);
            int rounds = extractInt(response, ReflectionMetadata.ROUNDS, 0);
            double score = extractDouble(response, ReflectionMetadata.SCORE, -1.0);

            long cost = System.currentTimeMillis() - start;

            log.info("[MultiAgent] 单 Agent 模式完成: role={}, requestId={}, rounds={}, score={}",
                    request.role(), request.requestId(), rounds,
                    String.format("%.2f", score));

            return AgentResponse.ok(
                    request.requestId(), request.role(),
                    finalAnswer, cost, rounds, score);

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[MultiAgent] 单 Agent 模式失败: role={}, requestId={}",
                    request.role(), request.requestId(), e);
            return AgentResponse.fail(
                    request.requestId(), request.role(),
                    "Agent 调用失败: " + e.getMessage(), cost);
        }
    }

    // ==================== 内部（从 D71 保留） ====================

    private String extractFinalAnswer(ChatClientResponse response) {
        if (response == null) {
            return "";
        }
        Object finalAnswer = response.context().get(ReflectionMetadata.FINAL_ANSWER);
        if (finalAnswer != null && !finalAnswer.toString().isBlank()) {
            return finalAnswer.toString();
        }
        if (response.chatResponse() == null
                || response.chatResponse().getResult() == null) {
            return "";
        }
        String text = response.chatResponse().getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private int extractInt(ChatClientResponse response, String key, int defaultVal) {
        if (response == null) return defaultVal;
        Object v = response.context().get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return defaultVal;
    }

    private double extractDouble(ChatClientResponse response, String key, double defaultVal) {
        if (response == null) return defaultVal;
        Object v = response.context().get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return defaultVal;
    }
}