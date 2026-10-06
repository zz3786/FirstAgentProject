package org.example.core.tools;

import lombok.extern.slf4j.Slf4j;
import org.example.core.retry.AiRetryTemplate;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * SafeToolCallback 工厂
 *
 * <h3>职责</h3>
 * 把"原始 ToolCallback 数组"包装成"带安全保护的 ToolCallback 数组"。
 * <p>
 * <b>为什么要有这个工厂</b>：
 * <ul>
 *   <li>ChatService / PlanChatClientConfig 都需要"批量包装工具"——逻辑重复</li>
 *   <li>把它们对 WorkflowRetryTemplate 的依赖收敛到工厂一处——调用方只认识工厂</li>
 *   <li>未来换重试实现（Resilience4j 等）——只改工厂</li>
 * </ul>
 * <p>
 * <b>不是 Spring Bean 的 SafeToolCallback 通过本工厂注册为组件依赖</b>——
 * 工厂是 Bean，SafeToolCallback 不是（每个工具一个实例）。
 */
@Slf4j
@Component
public class SafeToolCallbackFactory {

    private final AiRetryTemplate retryTemplate;

    public SafeToolCallbackFactory(AiRetryTemplate retryTemplate) {
        this.retryTemplate = retryTemplate;
        log.info("[SafeToolCallbackFactory] 初始化完成");
    }

    /**
     * 批量包装
     *
     * @param rawCallbacks 原始工具回调
     * @return 带超时+重试+日志+兜底的包装版
     */
    public ToolCallback[] wrap(ToolCallback[] rawCallbacks) {
        if (rawCallbacks == null || rawCallbacks.length == 0) {
            return new ToolCallback[0];
        }
        ToolCallback[] wrapped = Arrays.stream(rawCallbacks)
                .map(rc -> new SafeToolCallback(rc, retryTemplate))
                .toArray(ToolCallback[]::new);
        log.info("[SafeToolCallbackFactory] 包装 {} 个工具", wrapped.length);
        return wrapped;
    }

    /**
     * 单个包装——需要单独控制某个工具时用
     */
    public ToolCallback wrap(ToolCallback raw) {
        return new SafeToolCallback(raw, retryTemplate);
    }
}