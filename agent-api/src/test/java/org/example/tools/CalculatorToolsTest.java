package org.example.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CalculatorToolsTest {

    private CalculatorTools calculatorTools;

    @BeforeEach
    void setUp() {
        calculatorTools = new CalculatorTools();
    }

    @Test
    @DisplayName("加法：1 + 1 = 2")
    void testAdd() {
        String result = calculatorTools.calculate(1, 1, "add")+"";
        assertNotNull(result, "结果不应为空");
        assertTrue(result.contains("2"), "1+1 应包含 2，实际: " + result);
    }

    @Test
    @DisplayName("减法：10 - 3 = 7")
    void testSub() {
        String result = calculatorTools.calculate(10, 3, "sub")+"";
        assertTrue(result.contains("7"), "10-3 应包含 7，实际: " + result);
    }

    @Test
    @DisplayName("乘法：6 * 7 = 42")
    void testMul() {
        String result = calculatorTools.calculate(6, 7, "mul")+"";
        assertTrue(result.contains("42"), "6*7 应包含 42，实际: " + result);
    }

    @Test
    @DisplayName("除法：10 / 2 = 5")
    void testDiv() {
        String result = calculatorTools.calculate(10, 2, "div")+"";
        assertTrue(result.contains("5"), "10/2 应包含 5，实际: " + result);
    }

    @Test
    @DisplayName("除零：应返回友好提示，不应抛出异常")
    void testDivideByZero() {
        assertDoesNotThrow(
                () -> calculatorTools.calculate(10, 0, "div"),
                "除零不应抛出异常"
        );
        String result = calculatorTools.calculate(10, 0, "div")+"";
        // 根据你工具类实际返回调整断言
        assertNotNull(result);
        System.out.println("除零返回: " + result);
    }

    @Test
    @DisplayName("非法运算符：应返回友好提示，不应抛出异常")
    void testInvalidOperation() {
        assertDoesNotThrow(() -> calculatorTools.calculate(1, 1, "unknown"));
        String result = calculatorTools.calculate(1, 1, "unknown")+"";
        assertNotNull(result);
        System.out.println("非法操作返回: " + result);
    }
}
