package org.example.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.*;

class SafeToolCallbackTest {

    /** 构造一个假的 ToolCallback，方便模拟各种场景 */
    private static class FakeToolCallback implements ToolCallback {
        private final String name;
        private final String behavior; // "ok" / "slow" / "error"

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
                    try { Thread.sleep(60_000); } catch (InterruptedException e) {
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

    @Test
    @DisplayName("正常执行：应返回原始结果")
    void testNormalCall() {
        SafeToolCallback safe = new SafeToolCallback(new FakeToolCallback("demo", "ok"));
        String result = safe.call("{\"a\":1}");
        assertEquals("SUCCESS:{\"a\":1}", result);
    }

    @Test
    @DisplayName("异常执行：应返回友好提示，不抛出异常")
    void testErrorCall() {
        SafeToolCallback safe = new SafeToolCallback(new FakeToolCallback("demo", "error"));
        assertDoesNotThrow(() -> safe.call("{}"));
        String result = safe.call("{}");
        assertTrue(result.contains("执行失败"), "应包含失败提示，实际: " + result);
        assertTrue(result.contains("模拟工具失败"), "应包含具体原因");
    }

    @Test
    @DisplayName("超时执行：应返回友好超时提示")
    void testTimeoutCall() {
        // 需要用反射或者额外构造函数把 timeout 改成 1 秒，避免测试等 10 秒
        SafeToolCallback safe = new SafeToolCallback(new FakeToolCallback("demo", "slow"));
        String result = safe.call("{}");
        assertTrue(result.contains("超时"), "应包含超时提示，实际: " + result);
    }

    @Test
    @DisplayName("getToolDefinition 应转发到 delegate")
    void testDefinitionDelegation() {
        SafeToolCallback safe = new SafeToolCallback(new FakeToolCallback("demo", "ok"));
        assertEquals("demo", safe.getToolDefinition().name());
    }
}