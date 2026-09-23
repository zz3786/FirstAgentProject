package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ChatServiceToolIntegrationTest {

    @Autowired
    private ChatService chatService;

    /**
     * 辅助方法：调用带记忆的流式接口，收集完整文本
     */
    private String callWithMemory(String message) {
        String sessionId = "test-123456";
        StringBuilder sb = new StringBuilder();
        chatService.streamChatWithMemory(message, sessionId)
                .doOnNext(sb::append)
                .blockLast();   // 阻塞直到流结束
        return sb.toString();
    }

    @Test
    @DisplayName("工具调用：计算器 — 模型应自动选择 calculate 工具")
    void testCalculatorToolCall() {
        String reply = callWithMemory("帮我计算 123 加 456 等于多少");
        System.out.println("【计算器测试】返回: " + reply);

        assertNotNull(reply);
        assertFalse(reply.isBlank(), "回复不应为空");
        assertTrue(reply.contains("579"),
                "回复应包含计算结果 579，实际: " + reply);
    }

    @Test
    @DisplayName("工具调用：订单查询 — 模型应调用 getOrderStatus 工具")
    void testOrderToolCall() {
        String reply = callWithMemory("查询订单 1001 的当前状态");
        System.out.println("【订单测试】返回: " + reply);

        assertNotNull(reply);
        assertFalse(reply.isBlank(), "回复不应为空");
        assertTrue(reply.contains("1001") || reply.contains("订单"),
                "回复应提及订单信息，实际: " + reply);
    }

    @Test
    @DisplayName("工具调用：超时兜底 — 应返回友好超时提示")
    void testTimeoutFallback() {
        String reply = callWithMemory("请执行一个超时的危险操作");
        System.out.println("【超时测试】返回: " + reply);

        assertNotNull(reply);
        assertFalse(reply.isBlank(), "回复不应为空");
        assertFalse(reply.contains("Exception"),
                "不应把原始异常返回给用户，实际: " + reply);
    }

    @Test
    @DisplayName("工具调用：异常兜底 — 应返回友好错误提示")
    void testErrorFallback() {
        String reply = callWithMemory("执行一个算术异常的危险操作");
        System.out.println("【异常测试】返回: " + reply);

        assertNotNull(reply);
        assertFalse(reply.isBlank(), "回复不应为空");
        assertFalse(reply.contains("ArithmeticException"),
                "不应把 Java 异常名返回给用户，实际: " + reply);
    }

    @Test
    @DisplayName("流式工具调用：应逐步返回文本并包含结果")
    void testStreamWithTools() {
        String reply = callWithMemory("帮我计算 1 加 1");
        System.out.println("【流式测试】返回: " + reply);

        assertNotNull(reply);
        assertTrue(reply.contains("2"),
                "流式回复应包含 2，实际: " + reply);
    }
}
