package org.example.multiagent.supervisor;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.bus.AgentMessageBus;
import org.example.multiagent.bus.AgentMessageConverter;
import org.example.multiagent.config.MultiAgentProperties;
import org.example.multiagent.model.AgentMessage;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;
import org.example.multiagent.model.SubTask;
import org.example.multiagent.model.TaskGraph;
import org.example.multiagent.worker.WorkerAgent;
import org.example.multiagent.worker.WorkerAgentRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 任务分发器（D72 首版，D74 融合 MQ 通道）
 *
 * <h3>两种分发通道</h3>
 * <pre>
 *   viaMq=false（默认）：直接方法调用
 *      dispatchOne → worker.execute(subRequest) → 返回
 *
 *   viaMq=true：走 RabbitMQ
 *      dispatchOne → bus.sendAndWait(task, timeout)
 *                        ↓
 *                  Worker 侧 Consumer 收到 → 执行 → 发 RESULT
 *                        ↓
 *                  ResultCorrelationListener 完成 Future
 *                        ↓
 *                  返回 → 转 AgentResponse
 * </pre>
 *
 * <h3>为什么默认直调</h3>
 * <p>
 * 单进程部署时 MQ 只带来负担——序列化 + 网络往返 ~10-50ms，
 * 换来 0 收益（Worker 就在同 JVM）。
 * <p>
 * 只有 Worker 独立部署时才值得打开。见 {@code MultiAgentProperties.Supervisor.viaMq}。
 *
 * <h3>ObjectProvider 的必要性</h3>
 * <p>
 * {@code AgentMessageBus} 在 {@code app.agent-bus.rabbit.enabled=false} 时
 * 不会注册 RabbitMQ 实现——但 InMemoryAgentMessageBus 会作为兜底存在。
 * 用 {@code ObjectProvider} 注入保持宽松——即使 Bus 完全缺失（理论上不会）也不阻断启动。
 */
@Slf4j
@Component
public class TaskDispatcher {

    private final WorkerAgentRegistry registry;
    private final MultiAgentProperties props;
    private final ObjectProvider<AgentMessageBus> busProvider;
    private final AgentMessageConverter messageConverter;
    private final ExecutorService virtualThreadExecutor;

    public TaskDispatcher(WorkerAgentRegistry registry,
                          MultiAgentProperties props,
                          ObjectProvider<AgentMessageBus> busProvider,
                          AgentMessageConverter messageConverter) {
        this.registry = registry;
        this.props = props;
        this.busProvider = busProvider;
        this.messageConverter = messageConverter;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    // ==================== 主入口 ====================

    public List<AgentResponse> dispatch(TaskGraph graph, AgentRequest parentRequest) {
        if (graph == null || graph.isEmpty()) {
            return List.of();
        }

        long start = System.currentTimeMillis();
        long totalTimeoutMs = props.getSupervisor().getTotalTimeoutMs();
        long perSubtaskTimeoutMs = props.getSupervisor().getPerSubtaskTimeoutMs();
        boolean viaMq = props.getSupervisor().isViaMq();

        log.info("[TaskDispatcher] 开始分发 {} 个子任务（通道={}）",
                graph.size(), viaMq ? "RabbitMQ" : "直调");

        // ① 为每个子任务创建 CompletableFuture
        List<CompletableFuture<AgentResponse>> futures = new ArrayList<>();
        for (SubTask subtask : graph.subtasks()) {
            futures.add(dispatchOne(subtask, parentRequest, perSubtaskTimeoutMs, viaMq));
        }

        // ② 整体超时等待
        CompletableFuture<Void> all = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));

        try {
            all.orTimeout(totalTimeoutMs, TimeUnit.MILLISECONDS).join();
        } catch (Exception e) {
            log.warn("[TaskDispatcher] 整体超时或异常: {}", e.getMessage());
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

        // ④ 按子任务 id 排序
        responses.sort(Comparator.comparing(r -> {
            Object sid = r.metadata().get("subtaskId");
            return sid instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
        }));

        long cost = System.currentTimeMillis() - start;
        long successCount = responses.stream().filter(AgentResponse::success).count();
        log.info("[TaskDispatcher] 完成: 总数={}, 成功={}, 耗时={}ms（通道={}）",
                responses.size(), successCount, cost, viaMq ? "RabbitMQ" : "直调");

        return responses;
    }

    // ==================== 单子任务分发 ====================

    private CompletableFuture<AgentResponse> dispatchOne(
            SubTask subtask,
            AgentRequest parentRequest,
            long timeoutMs,
            boolean viaMq) {

        if (viaMq) {
            return CompletableFuture.supplyAsync(
                    () -> dispatchViaMq(subtask, parentRequest, timeoutMs),
                    virtualThreadExecutor);
        }
        return dispatchDirect(subtask, parentRequest)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
    }

    // ---------- 通道 1：同步直调 ----------

    private CompletableFuture<AgentResponse> dispatchDirect(
            SubTask subtask,
            AgentRequest parentRequest) {

        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();

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

            AgentRequest subRequest = parentRequest
                    .withInput(subtask.description())
                    .withRole(subtask.role())
                    .withMetadata("subtaskId", subtask.id())
                    .withMetadata("expectedOutput", subtask.expectedOutput());

            try {
                AgentResponse resp = worker.execute(subRequest);
                if (resp.metadata().get("subtaskId") == null) {
                    resp = resp.withMetadata("subtaskId", subtask.id());
                }

                long cost = System.currentTimeMillis() - start;
                log.info("[TaskDispatcher] 子任务 {} 完成（直调）: role={}, success={}, costMs={}",
                        subtask.id(), subtask.role(), resp.success(), cost);

                return resp;
            } catch (Exception e) {
                long cost = System.currentTimeMillis() - start;
                log.warn("[TaskDispatcher] 子任务 {} 失败（直调）: role={}, costMs={}, err={}",
                        subtask.id(), subtask.role(), cost, e.getMessage());
                return AgentResponse.fail(
                                parentRequest.requestId(), subtask.role(),
                                "子任务执行异常: " + e.getMessage(), cost)
                        .withMetadata("subtaskId", subtask.id());
            }
        }, virtualThreadExecutor);
    }

    // ---------- 通道 2：RabbitMQ ----------

    private AgentResponse dispatchViaMq(
            SubTask subtask,
            AgentRequest parentRequest,
            long timeoutMs) {

        long start = System.currentTimeMillis();

        // ① 拿 Bus——可能为 null（未装配）
        AgentMessageBus bus = busProvider.getIfAvailable();
        if (bus == null) {
            log.warn("[TaskDispatcher] AgentMessageBus 未装配——降级为直调");
            return dispatchDirect(subtask, parentRequest).join();
        }
        if (!bus.isAvailable()) {
            log.warn("[TaskDispatcher] Bus 不可用——降级为直调");
            return dispatchDirect(subtask, parentRequest).join();
        }

        // ② 构造子任务请求
        AgentRequest subRequest = parentRequest
                .withInput(subtask.description())
                .withRole(subtask.role())
                .withMetadata("subtaskId", subtask.id())
                .withMetadata("expectedOutput", subtask.expectedOutput());

        // ③ 转消息
        AgentMessage task = messageConverter.toTaskMessage(subRequest, AgentRole.SUPERVISOR.name());

        try {
            // ④ 发消息 + 等待回执
            AgentMessage result = bus.sendAndWait(task, Duration.ofMillis(timeoutMs));

            // ⑤ 回执转 AgentResponse
            AgentResponse resp = messageConverter.toAgentResponse(result);
            if (resp.metadata().get("subtaskId") == null) {
                resp = resp.withMetadata("subtaskId", subtask.id());
            }

            long cost = System.currentTimeMillis() - start;
            log.info("[TaskDispatcher] 子任务 {} 完成（MQ）: role={}, success={}, costMs={}",
                    subtask.id(), subtask.role(), resp.success(), cost);

            return resp;

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("[TaskDispatcher] 子任务 {} 失败（MQ）: role={}, costMs={}, err={}",
                    subtask.id(), subtask.role(), cost, e.getMessage());
            return AgentResponse.fail(
                            parentRequest.requestId(), subtask.role(),
                            "MQ 分发失败: " + e.getMessage(), cost)
                    .withMetadata("subtaskId", subtask.id());
        }
    }
}