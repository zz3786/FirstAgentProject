package org.example.observability.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.example.observability.config.TracingProperties;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具调用的 Tracing 装饰器（D77）
 *
 * <h3>包装层次</h3>
 * <pre>
 *   ChatClient
 *     → TracingToolCallback     ★ D77 新增（最外层）
 *       → AuthzToolCallback     D69
 *         → SafeToolCallback    D66
 *           → 原始工具
 * </pre>
 *
 * <h3>为什么 Tracing 在最外层</h3>
 * <ul>
 *   <li>记录"包括鉴权和重试在内的总耗时"——用户视角的真实代价</li>
 *   <li>如果放内层，鉴权失败/重试的场景 Span 缺失——无法诊断</li>
 * </ul>
 * <p>
 * <b>与 D69 的"鉴权在最外层"不冲突</b>——
 * 鉴权是"安全边界"，追踪是"观测边界"。
 * 观测边界可以比安全边界更外层——它不该改变任何业务语义。
 *
 * <h3>为什么不打完整入参</h3>
 * <p>
 * 工具入参可能含手机号、订单号、用户输入——
 * 完整记录会：
 * <ul>
 *   <li>泄漏隐私到 Zipkin</li>
 *   <li>撑爆 Span（大 JSON 入参会占用大量空间）</li>
 * </ul>
 * <p>
 * 只记录长度——需要细查时开 {@code max-input-tag-length} 临时调大。
 */
@Slf4j
public class TracedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final Tracer tracer;
    private final TracingProperties props;

    public TracedToolCallback(ToolCallback delegate, Tracer tracer, TracingProperties props) {
        this.delegate = delegate;
        this.tracer = tracer;
        this.props = props;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return doCall(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return doCall(toolInput, toolContext);
    }

    // ==================== 内部 ====================

    private String doCall(String toolInput, ToolContext toolContext) {
        if (!props.isEnabled() || !props.isTraceToolCalls()) {
            return toolContext == null
                    ? delegate.call(toolInput)
                    : delegate.call(toolInput, toolContext);
        }

        String toolName = delegate.getToolDefinition().name();
        Span span = tracer.nextSpan()
                .name(SpanNaming.TOOL_INVOKE_PREFIX + toolName)
                .tag(SpanNaming.TAG_TOOL_NAME, toolName)
                .start();

        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            // 入参处理——默认只记长度
            int inputLen = toolInput == null ? 0 : toolInput.length();
            span.tag(SpanNaming.TAG_TOOL_INPUT_LENGTH, String.valueOf(inputLen));
            if (props.getMaxInputTagLength() > 0 && toolInput != null) {
                span.tag("tool.input.preview",
                        toolInput.substring(0, Math.min(inputLen, props.getMaxInputTagLength())));
            }

            String result = toolContext == null
                    ? delegate.call(toolInput)
                    : delegate.call(toolInput, toolContext);

            if (props.isRecordToolOutcome()) {
                span.tag(SpanNaming.TAG_TOOL_SUCCESS, "true");
            }
            return result;

        } catch (Exception e) {
            span.error(e);
            if (props.isRecordToolOutcome()) {
                span.tag(SpanNaming.TAG_TOOL_SUCCESS, "false");
            }
            throw e;
        } finally {
            span.end();
        }
    }
}