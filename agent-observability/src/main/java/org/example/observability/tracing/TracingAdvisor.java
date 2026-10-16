package org.example.observability.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.example.observability.config.TracingProperties;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * ChatClient 链路追踪 Advisor（D77）
 *
 * <h3>职责</h3>
 * <p>
 * 在每次 ChatClient 调用周围创建一个 Span——记录：
 * <ul>
 *   <li>输入长度（不打内容——含用户隐私）</li>
 *   <li>输出长度</li>
 *   <li>耗时（Span 自动记录）</li>
 *   <li>异常</li>
 * </ul>
 *
 * <h3>为什么用 HIGHEST_PRECEDENCE</h3>
 * <p>
 * 要包住所有其他 Advisor——从"最外层"开始计时。
 * 这样 Span 时长 = 整个 LLM 链路的真实耗时（含记忆、RAG、工具等）。
 * <p>
 * 对比：如果放在最内层，Span 只覆盖"发 HTTP 到模型"这一段——
 * 用户看不到"为什么请求慢"的全貌。
 *
 * <h3>流式的 Span 生命周期</h3>
 * <p>
 * 同步调用：Span 从方法进入到返回——自然闭合。
 * <p>
 * <b>流式（SSE）</b>：Span 必须跟 Flux 的生命周期走——
 * 从订阅开始，到 onComplete / onError 结束。
 * <ul>
 *   <li>用 {@code Flux.defer(...)} 保证"每次订阅生成新 Span"</li>
 *   <li>{@code doOnComplete} / {@code doOnError} 关闭 Span</li>
 *   <li>不要用 {@code try-finally}——方法返回时 Flux 还没跑完</li>
 * </ul>
 *
 * <h3>为什么不用 @NewSpan 注解</h3>
 * <p>
 * Micrometer 提供 {@code @NewSpan} AOP 注解——但：
 * <ul>
 *   <li>对 {@code Flux} 的异步语义支持不完整</li>
 *   <li>同类内部调用不生效（AOP 代理限制）</li>
 *   <li>无法区分"同步 vs 流式"两种处理方式</li>
 * </ul>
 * 编程式更可控。
 */
@Slf4j
public class TracingAdvisor implements CallAdvisor, StreamAdvisor {

    private final Tracer tracer;
    private final TracingProperties props;

    public TracingAdvisor(Tracer tracer, TracingProperties props) {
        this.tracer = tracer;
        this.props = props;
    }

    @Override
    public String getName() {
        return "TracingAdvisor";
    }

    @Override
    public int getOrder() {
        // 最外层——包住所有业务 Advisor
        return Ordered.HIGHEST_PRECEDENCE;
    }

    // ==================== 同步调用 ====================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (!props.isEnabled() || !props.isTraceChatClient()) {
            return chain.nextCall(request);
        }

        Span span = tracer.nextSpan().name(SpanNaming.CHAT_CLIENT).start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            int inputLen = measureInput(request);
            span.tag(SpanNaming.TAG_CHAT_INPUT_LENGTH, String.valueOf(inputLen));

            ChatClientResponse response = chain.nextCall(request);

            int outputLen = measureOutput(response);
            span.tag(SpanNaming.TAG_CHAT_OUTPUT_LENGTH, String.valueOf(outputLen));

            return response;

        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    // ==================== 流式调用 ====================

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request,
                                                 StreamAdvisorChain chain) {

        log.info("[ENTER] {} order={}", getName(), getOrder());

        if (!props.isEnabled() || !props.isTraceChatClient()) {
            return chain.nextStream(request);
        }

        // ★ 用 defer 保证"每次订阅创建新 Span"——
        //   普通写法会在方法返回时就把 Span 创建好，导致 Span 被复用
        return Flux.defer(() -> {
            Span span = tracer.nextSpan().name(SpanNaming.CHAT_CLIENT_STREAM).start();
            Tracer.SpanInScope ws = tracer.withSpan(span);

            int inputLen = measureInput(request);
            span.tag(SpanNaming.TAG_CHAT_INPUT_LENGTH, String.valueOf(inputLen));

            // 累积输出长度——在 onComplete 时记录
            StringBuilder outputBuf = new StringBuilder();

            return chain.nextStream(request)
                    .doOnNext(resp -> {
                        String text = extractText(resp);
                        if (text != null) {
                            outputBuf.append(text);
                        }
                    })
                    .doOnComplete(() -> {
                        span.tag(SpanNaming.TAG_CHAT_OUTPUT_LENGTH,
                                String.valueOf(outputBuf.length()));
                        span.end();
                        ws.close();
                        log.info("[EXIT]  {} order={}", getName(), getOrder());
                    })
                    .doOnError(err -> {
                        span.error(err);
                        span.end();
                        ws.close();
                    })
                    .doOnCancel(() -> {
                        // 客户端提前断开——正常场景，不算错误
                        span.event("client.cancel");
                        span.end();
                        ws.close();
                    });
        });
    }

    // ==================== 辅助 ====================

    private int measureInput(ChatClientRequest request) {
        if (request == null || request.prompt() == null) return 0;
        return request.prompt().getInstructions().stream()
                .mapToInt(m -> m.getText() == null ? 0 : m.getText().length())
                .sum();
    }

    private int measureOutput(ChatClientResponse response) {
        String text = extractText(response);
        return text == null ? 0 : text.length();
    }

    private String extractText(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null) {
            return null;
        }
        return response.chatResponse().getResult().getOutput().getText();
    }
}