package org.example.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CalculatorTools {

    @Tool(description = "执行基本的数学运算，支持加(add)、减(sub)、乘(mul)、除(div)")
    public double calculate(
            @ToolParam(description = "第一个数字") double a,
            @ToolParam(description = "第二个数字") double b,
            @ToolParam(description = "运算类型，只能是 add、sub、mul、div 之一", required = true)
            String operation) {

        return switch (operation.toLowerCase()) {
            case "add" -> a + b;
            case "sub" -> a - b;
            case "mul" -> a * b;
            case "div" -> {
                if (b == 0) {
                    throw new ArithmeticException("除数不能为0");
                }
                yield a / b;
            }
            default -> throw new IllegalArgumentException("不支持的运算: " + operation);
        };
    }

}
