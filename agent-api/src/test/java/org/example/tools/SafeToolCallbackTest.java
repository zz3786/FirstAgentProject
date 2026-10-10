package org.example.tools;

import org.example.core.retry.AiRetryTemplate;
import org.example.core.retry.config.RetryProperties;
import org.example.core.tools.SafeToolCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SafeToolCallback 单元测试（D66 后重写）
 *
 * <h3>为什么重写</h3>
 * <p>
 * D66 引入重试机制后，{@link SafeToolCallback} 的构造函数从
 * {@code SafeToolCallback(ToolCallback)} 变成
 * {@code SafeToolCallback(ToolCallback, AiRetryTemplate)}。
 * 原测试用单参构造，编译不过。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>不启动 Spring 容器</b>——纯 JUnit 测试，毫秒级</li>
 *   <li><b>用 FakeToolCallback 模拟各种行为</b>——ok / error / slow</li>
 *   <li><b>重试逻辑通过 RetryProperties 控制</b>——正常/异常测试关重试，
 *       超时测试只尝试一次——避免测试等太久</li>
 *   <li><b>超时测试标记 {@code @Tag("slow")}</b>——需要 5 秒，
 *       CI 里可按需过滤</li>
 * </ul>
 */
class SafeToolCallbackTest {

    // ==================== 测试替身 ====================

    /**
     * 假的 ToolCallback——模拟三种行为。
     */
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
                    // ★ 用 6 秒——比 SafeToolCallback.TIMEOUT_SECONDS（5 秒）长 1 秒
                    //   测试超时行为；不要用 60 秒——超时逻辑失效时会让测试挂很久
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

    /**
     * 关闭重试的 RetryTemplate——让每次调用只执行一次，
     * 异常立即返回，测试速度快。
     */
    private static AiRetryTemplate noRetryTemplate() {
        RetryProperties props = new RetryProperties();
        props.setEnabled(false);   // ★ 关闭重试
        return new AiRetryTemplate(props);
    }

    /**
     * 单次尝试的 RetryTemplate——用于超时测试。
     * <p>
     * 不关重试（避免和"关闭重试"语义混淆），
     * 但把 maxAttempts 设为 1——只跑一次，
     * 避免超时测试等 3 × 5 = 15 秒。
     */
    private static AiRetryTemplate singleAttemptTemplate() {
        RetryProperties props = new RetryProperties();
        props.setEnabled(true);
        props.setDefaultMaxAttempts(1);   // ★ 只尝试一次
        return new AiRetryTemplate(props);
    }

    // ==================== 测试用例 ====================

    @Test
    @DisplayName("正常执行：应返回原始结果")
    void testNormalCall() {
        SafeToolCallback safe = new SafeToolCallback(
                new FakeToolCallback("demo", "ok"),
                noRetryTemplate());

        String result = safe.call("{\"a\":1}");

        assertEquals("SUCCESS:{\"a\":1}", result,
                "正常调用应原样返回结果");
    }

    @Test
    @DisplayName("异常执行：应返回友好提示，不抛出异常")
    void testErrorCall() {
        SafeToolCallback safe = new SafeToolCallback(
                new FakeToolCallback("demo", "error"),
                noRetryTemplate());

        // ① 不抛异常
        assertDoesNotThrow(() -> safe.call("{}"),
                "工具异常应被 SafeToolCallback 吞掉，返回文本");

        // ② 返回文本包含失败提示 + 具体原因
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
        // ★ 前置说明：
        //   - SafeToolCallback.TIMEOUT_SECONDS = 5
        //   - FakeToolCallback "slow" 分支 sleep 6 秒
        //   - singleAttemptTemplate 只尝试一次
        //   - 整个测试约需 5 秒（超时后立即返回）
        SafeToolCallback safe = new SafeToolCallback(
                new FakeToolCallback("demo", "slow"),
                singleAttemptTemplate());

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
                noRetryTemplate());

        assertEquals("demo", safe.getToolDefinition().name(),
                "getToolDefinition 应原样转发");
        assertEquals("测试用工具", safe.getToolDefinition().description(),
                "description 也应转发");
    }
}