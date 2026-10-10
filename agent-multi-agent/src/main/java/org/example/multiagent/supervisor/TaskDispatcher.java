package org.example.multiagent.supervisor;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.config.MultiAgentProperties;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.SubTask;
import org.example.multiagent.model.TaskGraph;
import org.example.multiagent.worker.WorkerAgent;
import org.example.multiagent.worker.WorkerAgentRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 任务分发器（D72）
 *
 * <h3>职责</h3>
 * <p>
 * 接收 {@link TaskGraph}，把每个子任务分发给对应的 Worker，
 * <b>并行执行</b>，收集所有 {@link AgentResponse}。
 *
 * <h3>并行策略</h3>
 * <p>
 * 用 Java 21 虚拟线程执行——IO 密集场景（LLM 调用）的天然选择。
 * 子任务之间无依赖，可以全并行。
 *
 * <h3>超时控制</h3>
 * <ul>
 *   <li>单子任务：{@code props.supervisor.perSubtaskTimeoutMs}（默认 60s）</li>
 *   <li>整体：{@code props.supervisor.totalTimeoutMs}（默认 180s）</li>
 * </ul>
 * 单子任务超时→该 Worker 返回 fail 响应，不影响其他；
 * 整体超时→所有未完成的被取消。
 *
 * <h3>为什么用 CompletableFuture 而不是 ParallelExecutor</h3>
 * <p>
 * 项目里的 {@code ParallelExecutor} 是给"工作流内的数据拉取"用的——
 * 有 {@code ParallelTask} 类型、有 critical/fallback 概念。
 * Supervisor 的子任务是"Agent 调用"，语义不同，强行复用会让两边都变复杂。
 * <p>
 * 且 D74 会把这个类改成"发 RabbitMQ 消息"——届时并行机制完全变样。
 * 现在自己写 CompletableFuture 更清晰。
 *
 * <h3>异常隔离</h3>
 * <p>
 * 单个 Worker 抛异常 → 捕获后转为 fail 响应。一个失败不影响其他。
 */
@Slf4j
@Component
public class TaskDispatcher {

    private final WorkerAgentRegistry registry;
    private final MultiAgentProperties props;
    private final ExecutorService virtualThreadExecutor;

    public TaskDispatcher(WorkerAgentRegistry registry,
                          MultiAgentProperties props) {
        this.registry = registry;
        this.props = props;
        // Java 21 虚拟线程——每个子任务一个虚拟线程，不池化
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 分发所有子任务
     *
     * @param graph        任务图
     * @param parentRequest 上游请求（携带 fullUserId 等上下文）
     * @return 所有 Worker 的响应（按子任务 id 排序）；无子任务时返回空列表
     */
    public List<AgentResponse> dispatch(TaskGraph graph, AgentRequest parentRequest) {
        if (graph == null || graph.isEmpty()) {
            return List.of();
        }

        long start = System.currentTimeMillis();
        long totalTimeoutMs = props.getSupervisor().getTotalTimeoutMs();
        long perSubtaskTimeoutMs = props.getSupervisor().getPerSubtaskTimeoutMs();

        log.info("[TaskDispatcher] 开始分发 {} 个子任务", graph.size());

        // ① 为每个子任务创建 CompletableFuture
        List<CompletableFuture<AgentResponse>> futures = new ArrayList<>();
        for (SubTask subtask : graph.subtasks()) {
            futures.add(dispatchOne(subtask, parentRequest, perSubtaskTimeoutMs));
        }

        // ② 整体超时等待
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

        try {
            all.orTimeout(totalTimeoutMs, TimeUnit.MILLISECONDS).join();
        } catch (Exception e) {
            log.warn("[TaskDispatcher] 整体超时或异常: {}", e.getMessage());
            // 继续收集已完成的结果
        }

        // ③ 收集结果
        List<AgentResponse> responses = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<AgentResponse> f = futures.get(i);
            SubTask st = graph.subtasks().get(i);

            if (f.isDone() && !f.isCompletedExceptionally()) {
                AgentResponse resp = f.getNow(null);
                if (resp != null) {
                    responses.add(resp);
                    continue;
                }
            }
            // 未完成或被取消——构造超时响应
            responses.add(AgentResponse.fail(
                    parentRequest.requestId(),
                    st.role(),
                    "子任务 " + st.id() + " 超时或被取消",
                    System.currentTimeMillis() - start));
        }

        // ④ 按子任务 id 排序（保证顺序稳定）
        responses.sort(Comparator.comparing(r -> {
            // 从 metadata 中取 subtaskId（dispatchOne 里塞入）
            Object sid = r.metadata().get("subtaskId");
            return sid instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
        }));

        long cost = System.currentTimeMillis() - start;
        long successCount = responses.stream().filter(AgentResponse::success).count();
        log.info("[TaskDispatcher] 完成: 总数={}, 成功={}, 耗时={}ms",
                responses.size(), successCount, cost);

        return responses;
    }

    // ==================== 内部 ====================

    /**
     * 分发单个子任务
     */
    private CompletableFuture<AgentResponse> dispatchOne(
            SubTask subtask,
            AgentRequest parentRequest,
            long timeoutMs) {

        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();

            // ① 找 Worker
            Optional<WorkerAgent> workerOpt = registry.get(subtask.role());
            if (workerOpt.isEmpty()) {
                log.warn("[TaskDispatcher] 无可用 Worker: role={}, subtaskId={}",
                        subtask.role(), subtask.id());
                return AgentResponse.fail(
                                parentRequest.requestId(), subtask.role(),
                                "无可用 Worker 处理角色 " + subtask.role(),
                                System.currentTimeMillis() - start)
                        .withMetadata("subtaskId", subtask.id());
            }
            WorkerAgent worker = workerOpt.get();

            // ② 构造子任务请求
            AgentRequest subRequest = parentRequest
                    .withInput(subtask.description())
                    .withRole(subtask.role())
                    .withMetadata("subtaskId", subtask.id())
                    .withMetadata("expectedOutput", subtask.expectedOutput());

            // ③ 调用——捕获所有异常
            try {
                AgentResponse resp = worker.execute(subRequest);
                // 保证 metadata 带 subtaskId（worker 可能没带）
                if (resp.metadata().get("subtaskId") == null) {
                    resp = resp.withMetadata("subtaskId", subtask.id());
                }

                // ★ D72 修订：子任务完成日志——用于诊断"哪个子任务慢"
                long cost = System.currentTimeMillis() - start;
                log.info("[TaskDispatcher] 子任务 {} 完成: role={}, success={}, costMs={}",
                        subtask.id(), subtask.role(), resp.success(), cost);

                return resp;
            } catch (Exception e) {
                log.warn("[TaskDispatcher] 子任务失败: subtaskId={}, worker={}, err={}",
                        subtask.id(), worker.getClass().getSimpleName(), e.getMessage());
                return AgentResponse.fail(
                                parentRequest.requestId(), subtask.role(),
                                "子任务执行异常: " + e.getMessage(),
                                System.currentTimeMillis() - start)
                        .withMetadata("subtaskId", subtask.id());
            }
        }, virtualThreadExecutor).orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
    }
}