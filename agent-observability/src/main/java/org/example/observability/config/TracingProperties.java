package org.example.observability.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 链路追踪配置（D77）
 *
 * <h3>yml 前缀</h3>
 * <pre>
 * app:
 *   observability:
 *     tracing:
 *       enabled: true
 *       trace-chat-client: true
 *       trace-tool-calls: true
 *       max-input-tag-length: 200
 * </pre>
 *
 * <h3>为什么拆多个开关</h3>
 * <p>
 * 排查问题时可能只想开某一类 Span——比如"只看工具调用"。
 * 全部埋点都开会增加 Span 数量——高频业务下采样率 0.1 也可能
 * 打爆 Zipkin。分开开关让运维能精细控制。
 *
 * <h3>与 management.tracing.sampling.probability 的区别</h3>
 * <ul>
 *   <li>{@code management.tracing.sampling.*}——Spring Boot 的全局采样率</li>
 *   <li>{@code app.observability.tracing.*}——本项目的"埋点开关"</li>
 * </ul>
 * <p>
 * 采样率决定"某条 trace 记不记"——采样率 0.1 时 90% 的请求不生成 Span。
 * 埋点开关决定"某一类操作要不要生成 Span"——关闭则即使采样到了也不生成。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.observability.tracing")
public class TracingProperties {

    /** 总开关——关闭后所有自定义埋点跳过 */
    private boolean enabled = true;

    /** 是否追踪 ChatClient 调用 */
    private boolean traceChatClient = true;

    /** 是否追踪工具调用 */
    private boolean traceToolCalls = true;

    /**
     * 工具入参在 Span tag 里的最大长度
     * <p>
     * <b>安全考虑</b>：工具入参可能含手机号、订单号、业务数据——
     * 完整记录会泄漏。默认只记录长度。
     * <p>
     * 需要调试时可以临时调大——但<b>生产环境不要开</b>。
     */
    private int maxInputTagLength = 0;   // 0 = 只记长度，不记内容

    /**
     * 是否在工具 Span 里记录成功/失败 tag
     * <p>
     * 关闭时只记"调用发生"——省一点 Span 大小。
     */
    private boolean recordToolOutcome = true;
}