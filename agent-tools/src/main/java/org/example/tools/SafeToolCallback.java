package org.example.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.*;

/**
 * 工具回调安全包装
 * <p>
 * <b>职责</b>：给原始 ToolCallback 加"超时控制 + 入参出参日志 + 异常兜底"。
 * <p>
 * <b>关键：两个 call 都要重写</b>：
 * <ul>
 *   <li>{@code call(String)}——无 ToolContext 的场景</li>
 *   <li>{@code call(String, ToolContext)}——带 ToolContext 的场景（D49 新增）</li>
 * </ul>
 * 如果只重写第一个，Spring AI 想传 ToolContext 时会被拦住——
 * 报错 "ToolContext is required by the method as an argument"。
 */
@Slf4j
public class SafeToolCallback implements ToolCallback {

    private static final long TIMEOUT_SECONDS = 10;

    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            16, 32,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200),
            r -> { Thread t = new Thread(r, "tool-callback-worker"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final ToolCallback delegate;

    public SafeToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    // ==================== 无 ToolContext ====================

    @Override
    public String call(String toolInput) {
        return executeSafely(toolInput, () -> delegate.call(toolInput));
    }

    // ==================== 带 ToolContext（★ 新增） ====================

    /**
     * 带 ToolContext 的调用
     * <p>
     * <b>为什么必须重写</b>：
     * Spring AI 框架在检测到工具方法有 ToolContext 参数时，
     * 会调用这个重载版本——如果 SafeToolCallback 没重写，
     * 框架调的是接口的 default 实现——大概率抛异常。
     * <p>
     * <b>透传原则</b>：ToolContext 原样传给 delegate——不解析、不修改。
     * 它是"系统信息通道"——工具方法自己负责取需要的 key。
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return executeSafely(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    // ==================== 统一的执行骨架 ====================

    /**
     * 执行骨架——超时控制 + 日志 + 异常兜底
     * <p>
     * <b>为什么抽出来</b>：两个 call 重载逻辑完全一致——
     * 只差"调哪个 delegate 方法"。用 Supplier 参数化差异。
     */
    private String executeSafely(String toolInput, Callable<String> action) {
        String name = delegate.getToolDefinition().name();
        long start = System.currentTimeMillis();

        log.info("🎯 [工具调用开始] {} | 入参: {}", name, toolInput);

        Future<String> future = EXECUTOR.submit(action);

        try {
            String result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long cost = System.currentTimeMillis() - start;

            String displayResult = result;
            if (result != null && result.length() > 5000) {
                displayResult = result.substring(0, 5000)
                        + "...(已截断，完整长度: " + result.length() + ")";
            }

            log.info("✅ [工具调用成功] {} | 耗时: {}ms | 出参: {}",
                    name, cost, displayResult);
            return result;

        } catch (TimeoutException e) {
            future.cancel(true);
            long cost = System.currentTimeMillis() - start;
            log.warn("⏰ [工具调用超时] {} | 耗时: {}ms | 超时阈值: {}s",
                    name, cost, TIMEOUT_SECONDS);
            return "⚠️ 工具 " + name + " 执行超时（超过 " + TIMEOUT_SECONDS + " 秒），请稍后重试或换一种方式提问。";

        } catch (ExecutionException e) {
            long cost = System.currentTimeMillis() - start;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("❌ [工具调用失败] {} | 耗时: {}ms | 原因: {}",
                    name, cost, cause.getMessage(), cause);
            return "⚠️ 工具 " + name + " 执行失败：" + cause.getMessage() + "。请检查输入参数或稍后重试。";

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ [工具调用被中断] {}", name, e);
            return "⚠️ 工具 " + name + " 执行被中断，请重新提问。";

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("❌ [工具调用未知异常] {} | 耗时: {}ms", name, cost, e);
            return "⚠️ 工具 " + name + " 出现未知错误，请稍后重试。";
        }
    }
}