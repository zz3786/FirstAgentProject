package org.example.multiagent.worker;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.example.core.rbac.ToolAuthorizer;
import org.example.core.rbac.wrapper.AuthzToolCallback;
import org.example.core.toolprofile.ToolProfileResolver;
import org.example.core.tools.SafeToolCallbackFactory;
import org.example.multiagent.config.WorkerConfig;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;
import org.example.observability.tracing.SpanNaming;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.Map;

/**
 * Worker Agent 模板基类（D73）
 *
 * <h3>模板方法模式</h3>
 * <p>
 * {@link #execute(AgentRequest)} 是 final 的——子类不能改。它封装：
 * <ol>
 *   <li>取工具——从 ToolProfileResolver 按 consumerName 拿</li>
 *   <li>包装工具——Safe（重试/超时/日志/兜底）+ Authz（工具级/参数级鉴权）</li>
 *   <li>构造 ToolContext——userId + securityLevel</li>
 *   <li>调 ChatClient——注入工具、上下文、子任务描述</li>
 *   <li>计时 + 日志 + 异常兜底</li>
 * </ol>
 * <p>
 * 子类只需实现：
 * <ul>
 *   <li>{@link #role()}——返回自己的角色</li>
 *   <li>{@link #chatClient()}——返回自己的 ChatClient</li>
 *   <li>{@link #consumerName()}——返回 ToolProfile 消费方名</li>
 * </ul>
 *
 * <h3>工具装配链——复用 D69 的三层包装</h3>
 * <pre>
 *   ToolProfileResolver.resolveForConsumer("worker-financial")
 *      ↓ ToolCallback[]（原始）
 *   SafeToolCallbackFactory.wrap(...)                 ← 重试/超时/日志
 *      ↓ ToolCallback[]
 *   AuthzToolCallback（本方法包装）                    ← 工具级 + 参数级 RBAC
 *      ↓ ToolCallback[]
 *   传给 ChatClient
 * </pre>
 * <p>
 * 与 ChatService 的包装链<b>完全一致</b>——保证 Worker 里的工具调用
 * 也经过鉴权、也享受重试/超时保护。这是"RBAC 覆盖所有工具调用入口"的关键。
 *
 * <h3>为什么每次调用都重新包装</h3>
 * <p>
 * D68 支持运行时刷新工具清单。如果启动时缓存 {@code ToolCallback[]}，
 * MCP Server 上下线工具后 Worker 看不到变化。
 * <p>
 * 每次调用的成本：
 * <ul>
 *   <li>{@code resolveForConsumer}——内存查表，O(n)</li>
 *   <li>{@code SafeToolCallbackFactory.wrap}——new N 个装饰器对象</li>
 *   <li>{@code AuthzToolCallback}——new N 个装饰器对象</li>
 * </ul>
 * 总计微秒级——相比 LLM 调用的几十秒，可以忽略。
 *
 * <h3>为什么不在这里控制超时</h3>
 * <p>
 * 上层 {@code TaskDispatcher} 已经用 {@code CompletableFuture.orTimeout} 控制。
 * 这里再加一层会重复——且子类无法知道"全局还剩多少时间"。
 * <p>
 * 工具级的超时由 {@code SafeToolCallback} 内部处理（5 秒单次）。
 *
 * <h3>securityLevel 从哪来</h3>
 * <p>
 * {@code AgentRequest.metadata} 里取——约定 key 是 {@code "securityLevel"}。
 * 找不到时兜底为 1（最低密级）——安全兜底。
 * <p>
 * 之所以不用强类型字段，是因为 D71 的 {@code AgentRequest} record 已经定型，
 * 加字段会影响所有调用方——用 metadata 灵活性更好。
 */
@Slf4j
public abstract class AbstractWorkerAgent implements WorkerAgent {

    private final ChatClient chatClient;
    private final ToolProfileResolver toolProfileResolver;
    private final SafeToolCallbackFactory safeToolCallbackFactory;
    private final ToolAuthorizer toolAuthorizer;
    private final Tracer tracer;

    protected AbstractWorkerAgent(
            ChatClient chatClient,
            ToolProfileResolver toolProfileResolver,
            SafeToolCallbackFactory safeToolCallbackFactory,
            ToolAuthorizer toolAuthorizer,
            WorkerConfig config,
            ObjectProvider<Tracer> tracerProvider) {

        // 配置校验——启动时暴露问题
        if (config == null) {
            throw new IllegalStateException(
                    getClass().getSimpleName() + " 的 WorkerConfig 为空");
        }
        if (!config.isValid()) {
            throw new IllegalStateException(
                    getClass().getSimpleName() + " 的 WorkerConfig 不完整: "
                            + "role=" + config.getRole()
                            + ", toolProfile=" + config.getToolProfile()
                            + ", systemPrompt=" + (config.getSystemPrompt() == null ? "null" : "set"));
        }

        this.chatClient = chatClient;
        this.toolProfileResolver = toolProfileResolver;
        this.safeToolCallbackFactory = safeToolCallbackFactory;
        this.toolAuthorizer = toolAuthorizer;
        this.tracer = tracerProvider.getIfAvailable();

        log.info("[{}] 初始化完成: role={}, profile={}",
                getClass().getSimpleName(), config.getRole(), config.getToolProfile());
    }

    // ==================== 子类实现 ====================

    /** Worker 角色——用于 WorkerAgentRegistry 索引 */
    @Override
    public abstract AgentRole role();

    /** 本 Worker 的 ChatClient——装配时注入 */
    protected abstract ChatClient chatClient();

    /** ToolProfile 消费方名——如 "worker-financial" */
    protected abstract String consumerName();

    // ==================== 模板方法 ====================


    @Override
    public final AgentResponse execute(AgentRequest request) {
        // ★ D77：Worker 层 Span——每个角色独立命名
        Span span = null;
        if (tracer != null) {
            String spanName = SpanNaming.AGENT_WORKER_PREFIX
                    + role().name().toLowerCase();
            span = tracer.nextSpan()
                    .name(spanName)
                    .tag(SpanNaming.TAG_AGENT_ROLE, role().name())
                    .tag(SpanNaming.TAG_AGENT_REQUEST_ID, request.requestId())
                    .start();
        }

        try (Tracer.SpanInScope ws = span != null
                ? tracer.withSpan(span) : null) {
            return doExecute(request);
        } finally {
            if (span != null) span.end();
        }
    }

    public final AgentResponse doExecute(AgentRequest request) {
        long start = System.currentTimeMillis();

        try {
            // ① 取工具（每次调用都取——支持 D68 刷新）
            ToolCallback[] wrappedCallbacks = buildWrappedCallbacks();

            // ② 构造 ToolContext
            Map<String,Object> toolContext = buildToolContext(request);

            // ③ 调 LLM
            String answer = chatClient().prompt()
                    .user(request.input())
                    .toolCallbacks(wrappedCallbacks)
                    .toolContext(toolContext)
                    .call()
                    .content();

            long cost = System.currentTimeMillis() - start;

            if (answer == null || answer.isBlank()) {
                log.warn("[{}] 返回空答案: requestId={}, costMs={}",
                        getClass().getSimpleName(), request.requestId(), cost);
                return AgentResponse.fail(
                        request.requestId(), role(),
                        "Worker 返回空答案", cost);
            }

            log.info("[{}] 完成: requestId={}, tools={}, answerLen={}, costMs={}",
                    getClass().getSimpleName(), request.requestId(),
                    wrappedCallbacks.length, answer.length(), cost);

            return AgentResponse.ok(
                    request.requestId(), role(),
                    answer, cost, 0, -1.0);

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[{}] 执行失败: requestId={}, costMs={}",
                    getClass().getSimpleName(), request.requestId(), cost, e);
            return AgentResponse.fail(
                    request.requestId(), role(),
                    "Worker 执行异常: " + e.getMessage(), cost);
        }
    }

    // ==================== 内部 ====================

    /**
     * 构造带完整包装的工具回调数组
     * <p>
     * 每次调用都重新构造——支持运行时刷新。
     */
    private ToolCallback[] buildWrappedCallbacks() {
        // ① 从 profile 取原始工具
        ToolCallback[] raw = toolProfileResolver.resolveForConsumer(consumerName());

        // ② Safe 包装
        ToolCallback[] safe = safeToolCallbackFactory.wrap(raw);

        // ③ Authz 包装
        return Arrays.stream(safe)
                .map(cb -> (ToolCallback) new AuthzToolCallback(cb, toolAuthorizer))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 构造 ToolContext
     * <p>
     * 从 request 提取 userId 和 securityLevel——供 AuthzToolCallback 使用。
     */
    private Map<String,Object> buildToolContext(AgentRequest request) {
        int securityLevel = extractSecurityLevel(request);
        return Map.of(
                "userId", request.fullUserId(),
                "securityLevel", securityLevel
        );
    }

    /**
     * 从 AgentRequest.metadata 提取 securityLevel
     * <p>
     * 找不到时兜底为 1（最低密级）——安全兜底。
     */
    private int extractSecurityLevel(AgentRequest request) {
        Object v = request.metadata().get("securityLevel");
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString());
            } catch (NumberFormatException ignore) {
                // fallthrough
            }
        }
        return 1;
    }
}