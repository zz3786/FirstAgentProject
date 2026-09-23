package org.example.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.*;

public class SafeToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SafeToolCallback.class);

    /** 单个工具的最大执行时间（秒） */
    private static final long TIMEOUT_SECONDS = 10;

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-callback-worker");
        t.setDaemon(true);
        return t;
    });

    private final ToolCallback delegate;

    public SafeToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        String name = delegate.getToolDefinition().name();
        long start = System.currentTimeMillis();

        // ① 打印入参
        log.info("🎯 [工具调用开始] {} | 入参: {}", name, toolInput);

        Future<String> future = EXECUTOR.submit(() -> delegate.call(toolInput));

        try {
            String result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long cost = System.currentTimeMillis() - start;

            // ===== 出参截断（只影响日志，不影响返回给模型的数据） =====
            String displayResult = result;
            if (result != null && result.length() > 5000) {
                displayResult = result.substring(0, 5000)
                        + "...(已截断，完整长度: " + result.length() + ")";
            }

            log.info("✅ [工具调用成功] {} | 耗时: {}ms | 出参: {}", name, cost, displayResult);
            return result;   // ← 注意这里返回的是完整 result，不是 displayResult

        } catch (TimeoutException e) {
            future.cancel(true);
            long cost = System.currentTimeMillis() - start;
            log.warn("⏰ [工具调用超时] {} | 耗时: {}ms | 超时阈值: {}s", name, cost, TIMEOUT_SECONDS);
            // 关键：返回友好字符串而不是抛异常，让大模型能继续回答
            return "⏰ 工具 " + name + " 执行超时（超过 " + TIMEOUT_SECONDS + " 秒），请稍后重试或换一种方式提问。";

        } catch (ExecutionException e) {
            long cost = System.currentTimeMillis() - start;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("❌ [工具调用失败] {} | 耗时: {}ms | 原因: {}", name, cost, cause.getMessage(), cause);
            return "❌ 工具 " + name + " 执行失败：" + cause.getMessage() + "。请检查输入参数或稍后重试。";

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ [工具调用被中断] {}", name, e);
            return "❌ 工具 " + name + " 执行被中断，请重新提问。";
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("❌ [工具调用未知异常] {} | 耗时: {}ms", name, cost, e);
            return "❌ 工具 " + name + " 出现未知错误，请稍后重试。";
        }
    }
}