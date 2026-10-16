package org.example.tools;

import org.example.core.retry.AiRetryTemplate;
import org.example.core.retry.config.RetryProperties;
import org.example.core.tools.SafeToolCallback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SafeToolCallback 单元测试（D66 后重写，D77 收尾改线程池）
 *
 * <h3>D77 收尾改动</h3>
 * <p>
 * {@code SafeToolCallback} 的构造器从 2 参
 * {@code (ToolCallback, AiRetryTemplate)}
 * 变成 3 参
 * {@code (ToolCallback, AiRetryTemplate, ExecutorService)}——
 * 静态池改成 Spring Bean 注入。
 * <p>
 * 本测试是纯 JUnit（不启 Spring），所以用
 * {@link Executors#newFixedThreadPool(int)} 手动创建测试线程池，
 * {@code @AfterAll} 关闭。
 *
 * <h3>为什么共享一个静态池</h3>
 * <p>
 * 4 个测试用例各自创建一个池也行——但共享一个更省资源，且
 * 测试方法之间是顺序执行的（JUnit 默认），无并发争抢。
 *
 * <h3>超时测试标记 {@code @Tag("slow")}</h3>
 * <p>
 * 需要 5 秒——CI 里可按需过滤。
 */
class SafeToolCallbackTest {

    /** 测试共享线程池——4 个线程够用 */
    private static final ExecutorService TEST_EXECUTOR =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "test-tool-callback");
                t.setDaemon(true);
                return t;
            });

    @AfterAll
    static void shutdownExecutor() throws InterruptedException {
        TEST_EXECUTOR.shutdownNow();
        TEST_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS);
    }

    // ==================== 测试替身 ====================

    private static class FakeToolCallback implements ToolCallback {

        private final String name;
        private final String behavior;  // "ok" / "slow" / "error"

        FakeToolCallback(String name, String behavior) {
            this.name = name;
            this.behavior = behavior;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return new ToolDefinition() {
                @Override public String name() { return name; }
                @Override public String description() { return "测试用工具"; }
                @Override public String inputSchema() { return "{}"; }
            };
        }

        @Override
        public String call(String toolInput) {
            switch (behavior) {
                case "slow":
                    try {
                        Thread.sleep(6_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("被中断", e);
                    }
                    return "不该到这里";
                case "error":
                    throw new RuntimeException("模拟工具失败");
                default:
                    return "SUCCESS:" + toolInput;
            }
        }
    }

    // ==================== 辅助构造 ====================

    private static AiRetryTemplate noRetryTemplate() {
        RetryProperties props = new RetryProperties();
        props.setEnabled(false);
        return new AiRetryTemplate(props);
    }

    private static AiRetryTemplate singleAttemptTemplate() {
        RetryProperties props = new RetryProperties();
        props.setEnabled(true);
        props.setDefaultMaxAttempts(1);
        return new AiRetryTemplate(props);
    }

    // ==================== 测试用例 ====================

    @Test
    @DisplayName("正常执行：应返回原始结果")
    void testNormalCall() {
        SafeToolCallback safe = new SafeToolCallback(
                new FakeToolCallback("demo", "ok"),
                noRetryTemplate(),
                TEST_EXECUTOR);                      // ★ 3 参

        String result = safe.call("{\"a\":1}");

        assertEquals("SUCCESS:{\"a\":1}", result,
                "正常调用应原样返回结果");
    }

    @Test
    @DisplayName("异常执行：应返回友好提示，不抛出异常")
    void testErrorCall() {
        SafeToolCallback safe = new SafeToolCallback(
                new FakeToolCallback("demo", "error"),
                noRetryTemplate(),
                TEST_EXECUTOR);                      // ★ 3 参

        assertDoesNotThrow(() -> safe.call("{}"),
                "工具异常应被 SafeToolCallback 吞掉，返回文本");

        String result = safe.call("{}");

        assertNotNull(result, "结果不应为 null");
        assertTrue(result.contains("失败"),
                "应包含失败提示，实际: " + result);
        assertTrue(result.contains("模拟工具失败"),
                "应包含具体原因，实际: " + result);
    }

    @Test
    @Tag("slow")
    @DisplayName("超时执行：应返回友好超时提示")
    void testTimeoutCall() {
        SafeToolCallback safe = new SafeToolCallback(
                new FakeToolCallback("demo", "slow"),
                singleAttemptTemplate(),
                TEST_EXECUTOR);                      // ★ 3 参

        String result = safe.call("{}");

        assertNotNull(result, "结果不应为 null");
        assertTrue(result.contains("超时") || result.contains("失败"),
                "应包含超时或失败提示，实际: " + result);
    }

    @Test
    @DisplayName("getToolDefinition 应转发到 delegate")
    void testDefinitionDelegation() {
        SafeToolCallback safe = new SafeToolCallback(
                new FakeToolCallback("demo", "ok"),
                noRetryTemplate(),
                TEST_EXECUTOR);                      // ★ 3 参

        assertEquals("demo", safe.getToolDefinition().name(),
                "getToolDefinition 应原样转发");
        assertEquals("测试用工具", safe.getToolDefinition().description(),
                "description 也应转发");
    }
}