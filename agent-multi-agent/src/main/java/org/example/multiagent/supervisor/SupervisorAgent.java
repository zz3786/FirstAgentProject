package org.example.multiagent.supervisor;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.aggregation.ResultAggregator;
import org.example.multiagent.aggregation.model.AggregationContext;
import org.example.multiagent.aggregation.model.AggregationResult;
import org.example.multiagent.config.MultiAgentProperties;
import org.example.multiagent.exception.AgentException;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.SupervisorResult;
import org.example.multiagent.model.TaskGraph;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;


/**
 * 主管 Agent（D72）
 *
 * <h3>流程</h3>
 * <pre>
 *   用户请求
 *      ↓
 *   ① TaskDecomposer.decompose(input)      → TaskGraph
 *      ↓
 *   ② TaskDispatcher.dispatch(graph)       → List&lt;AgentResponse&gt;
 *      ↓
 *   ③ synthesize(goal, responses)          → 最终答案
 *      ↓
 *   SupervisorResult
 * </pre>
 *
 * <h3>三步各自的定位</h3>
 * <ul>
 *   <li><b>拆解</b>：LLM 调用，把问题分解为独立子任务</li>
 *   <li><b>分发</b>：并行调用多个 Worker（虚拟线程）</li>
 *   <li><b>汇总</b>：LLM 调用，把多个 Worker 结果合成一段答案</li>
 * </ul>
 * <p>
 * 三次 LLM 调用：1 次拆解 + N 次 Worker + 1 次汇总。
 * 对用户来说，总耗时 ≈ max(拆解, 所有 Worker) + 汇总，
 * 而不是 sum——这是 Supervisor 模式相对 Plan 的核心优势。
 *
 * <h3>降级策略</h3>
 * <p>
 * 如果拆解失败（LLM 返回格式错误等）——降级为"单 Agent 直接回答"。
 * 用 {@code reflectiveChatClient} 走 D71 的老路，包装成 SupervisorResult。
 * <b>用户请求不失败</b>，只是失去"多视角"能力。
 *
 * <h3>汇总的 Prompt 工程</h3>
 * <p>
 * 汇总 Prompt 是 Supervisor 质量的"最后一公里"：
 * <ul>
 *   <li>Worker 返回的可能是"局部视角"的答案——需要拼接、去重、冲突消解</li>
 *   <li>如果某个 Worker 失败——汇总时要说明"该视角未覆盖"</li>
 *   <li>避免直接拼接（用户看到三个独立答案会困惑）——要整合成一段连贯的话</li>
 * </ul>
 */
@Slf4j
@Component
public class SupervisorAgent {

    /** 单 Worker 结果注入汇总 Prompt 时的最大字符数（防 Prompt 爆炸） */
    private static final int MAX_WORKER_RESULT_CHARS = 2000;

    private final TaskDecomposer decomposer;
    private final TaskDispatcher dispatcher;
    private final ChatClient fallbackClient;
    private final ResultAggregator resultAggregator;

    public SupervisorAgent(
            TaskDecomposer decomposer,
            TaskDispatcher dispatcher,
            @Qualifier("supervisorSynthesizerClient") ChatClient synthesizerClient,
            @Qualifier("reflectiveChatClient") ChatClient fallbackClient, ResultAggregator resultAggregator,
            MultiAgentProperties props) {
        this.decomposer = decomposer;
        this.dispatcher = dispatcher;
        this.fallbackClient = fallbackClient;
        this.resultAggregator = resultAggregator;
    }

    /**
     * 执行主管流程
     *
     * @param request 用户请求（input = 用户问题）
     * @return 主管结果——包含任务图、所有 Worker 响应、最终答案
     */
    public SupervisorResult execute(AgentRequest request) {
        long start = System.currentTimeMillis();

        // ① 拆解
        TaskGraph graph;
        try {
            graph = decomposer.decompose(request.input());
        } catch (AgentException e) {
            log.warn("[Supervisor] 拆解失败，降级为单 Agent 回答: {}", e.getMessage());
            return fallbackSingleAgent(request, start);
        }

        if (graph.isEmpty()) {
            log.warn("[Supervisor] 任务图为空，降级为单 Agent 回答");
            return fallbackSingleAgent(request, start);
        }

        // ② 分发
        List<AgentResponse> workerResponses = dispatcher.dispatch(graph, request);
        if (workerResponses.isEmpty()) {
            log.warn("[Supervisor] 无 Worker 响应，降级为单 Agent 回答");
            return fallbackSingleAgent(request, start);
        }

        // ③ 汇总——D75 改为走 ResultAggregator 策略
        AggregationContext aggCtx = new AggregationContext(graph.goal(), request, workerResponses);
        AggregationResult aggResult = resultAggregator.aggregate(aggCtx);
        String finalAnswer = aggResult.finalAnswer();

        log.info("[Supervisor] 聚合完成: strategy={}, success={}, costMs={}",
                aggResult.strategyName(), aggResult.success(), aggResult.costMs());

        long cost = System.currentTimeMillis() - start;
        log.info("[Supervisor] 完成: goal=[{}], 子任务={}, 成功={}, 耗时={}ms",
                graph.goal(), graph.size(),
                workerResponses.stream().filter(AgentResponse::success).count(),
                cost);

        return SupervisorResult.ok(
                request.requestId(), graph, workerResponses, finalAnswer, cost);
    }


    /**
     * todo 要不要删除
     * 构造"专家意见清单"
     * <p>
     * 每个 Worker 结果一段，带角色标识。失败的结果也列出（标记"未成功"）——
     * 让汇总 LLM 知道"这个视角缺了什么"。
     */
    private String buildOpinionsText(List<AgentResponse> responses) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (AgentResponse resp : responses) {
            sb.append("【专家 ").append(idx++).append("：")
                    .append(resp.role().getDisplayName()).append("】\n");

            if (resp.success()) {
                sb.append(truncate(resp.output(), MAX_WORKER_RESULT_CHARS)).append("\n\n");
            } else {
                sb.append("（未成功：").append(resp.error()).append("）\n\n");
            }
        }
        return sb.toString();
    }

    // ==================== 降级 ====================

    /**
     * 降级为单 Agent 回答（D71 老路）
     */
    private SupervisorResult fallbackSingleAgent(AgentRequest request, long start) {
        try {
            String answer = fallbackClient.prompt()
                    .user(request.input())
                    .call()
                    .content();

            long cost = System.currentTimeMillis() - start;

            AgentResponse resp = AgentResponse.ok(
                    request.requestId(), request.role(),
                    answer == null ? "（无回答）" : answer,
                    cost, 0, -1.0);

            return SupervisorResult.ok(
                    request.requestId(),
                    new TaskGraph("降级为单 Agent 回答", List.of()),
                    List.of(resp),
                    answer == null ? "（无回答）" : answer,
                    cost);

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[Supervisor] 降级也失败", e);
            return SupervisorResult.fail(request.requestId(),
                    "降级失败: " + e.getMessage(), cost);
        }
    }

    // ==================== 辅助 ====================

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}