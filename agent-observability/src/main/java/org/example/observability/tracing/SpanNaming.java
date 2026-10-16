package org.example.observability.tracing;

/**
 * Span 命名规范（D77）
 *
 * <h3>为什么集中定义</h3>
 * <p>
 * Zipkin UI 按 Span 名聚合。如果各处命名风格不一致（{@code chatClient} /
 * {@code chat_client} / {@code ChatClient}）——聚合失效。
 * 集中定义保证一致。
 *
 * <h3>命名规范</h3>
 * <ul>
 *   <li>全小写，用 {@code .} 分层</li>
 *   <li>前缀表示"哪个组件"：{@code chat.} / {@code tool.} / {@code agent.} / {@code mq.}</li>
 *   <li>动态部分（工具名、角色名）拼在后面</li>
 * </ul>
 */
public final class SpanNaming {

    private SpanNaming() {}

    // ==================== ChatClient ====================

    /** ChatClient 同步调用 */
    public static final String CHAT_CLIENT = "chat.client";

    /** ChatClient 流式调用 */
    public static final String CHAT_CLIENT_STREAM = "chat.client.stream";

    // ==================== 工具调用 ====================

    /** 工具调用前缀——完整名为 {@code tool.invoke.calculate} */
    public static final String TOOL_INVOKE_PREFIX = "tool.invoke.";

    // ==================== 多 Agent ====================

    /** 主管 Agent 流程 */
    public static final String AGENT_SUPERVISOR = "agent.supervisor";

    /** Worker Agent 执行前缀——{@code agent.worker.financial} */
    public static final String AGENT_WORKER_PREFIX = "agent.worker.";

    /** 任务分发 */
    public static final String AGENT_DISPATCH = "agent.dispatch";

    // ==================== RAG ====================

    /** 混合检索 */
    public static final String RAG_HYBRID_SEARCH = "rag.hybrid-search";

    // ==================== MQ ====================

    /** MQ 发送 */
    public static final String MQ_SEND = "mq.send";

    // ==================== Tag 键 ====================

    public static final String TAG_TOOL_NAME = "tool.name";
    public static final String TAG_TOOL_SUCCESS = "tool.success";
    public static final String TAG_TOOL_INPUT_LENGTH = "tool.input.length";
    public static final String TAG_CHAT_INPUT_LENGTH = "chat.input.length";
    public static final String TAG_CHAT_OUTPUT_LENGTH = "chat.output.length";
    public static final String TAG_AGENT_ROLE = "agent.role";
    public static final String TAG_AGENT_REQUEST_ID = "agent.request.id";
}