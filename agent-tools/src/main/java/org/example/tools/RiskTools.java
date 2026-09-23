package org.example.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public class RiskTools {

    private static final Random RANDOM = new Random();

    @Tool(description = "执行一个可能失败的危险操作，用于测试异常处理能力。传入 errorType 可指定故障类型。")
    public String riskyOperation(
            @ToolParam(description = "故障类型：arithmetic（算术异常）、timeout（超时）、random（随机）", required = false)
            String errorType) {

        try {
            String type = (errorType == null || errorType.isEmpty()) ? "random" : errorType;

            switch (type.toLowerCase()) {
                case "arithmetic":
                    // 模拟算术异常
                    int result = 10 / 0;
                    return "计算结果：" + result;

                case "timeout":
                    // 模拟超时（休眠11秒）
                    Thread.sleep(11000);
                    return "操作完成";

                case "random":
                default:
                    // 随机故障：30%概率成功，70%概率失败
                    if (RANDOM.nextInt(100) < 30) {
                        return "✅ 操作成功！随机数种子：" + RANDOM.nextInt(1000);
                    } else {
                        throw new RuntimeException("模拟随机故障：服务暂时不可用");
                    }
            }
        } catch (ArithmeticException e) {
            // 关键：捕获异常后返回友好信息，而不是把异常抛给大模型
            return "❌ 计算失败：除数不能为零，请检查输入参数";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "⏰ 操作超时：服务响应过慢，请稍后重试";
        } catch (RuntimeException e) {
            return "❌ " + e.getMessage();
        } catch (Exception e) {
            return "❌ 未知错误：" + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

}
