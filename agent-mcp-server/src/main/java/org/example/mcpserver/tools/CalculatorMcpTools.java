package org.example.mcpserver.tools;

import lombok.extern.slf4j.Slf4j;
import org.example.tools.CalculatorTools;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP 计算器工具（D65）
 *
 * <h3>设计原则：MCP 注解方法只做“薄壳”</h3>
 * <p>
 * 业务逻辑在 {@link CalculatorTools}（agent-tools 模块），
 * 本类只负责：
 * <ol>
 *   <li>用 {@code @McpTool} 声明这是一个 MCP 能力</li>
 *   <li>把参数透传给 CalculatorTools</li>
 *   <li>把返回值透传给 MCP 协议层</li>
 * </ol>
 *
 * <h3>为什么不直接在 CalculatorTools 上加 @McpTool</h3>
 * <p>
 * {@code CalculatorTools} 已经用 {@code @Tool} 暴露给内嵌 ChatClient。
 * 如果再加 {@code @McpTool}，{@code MethodToolCallbackProvider} 和
 * MCP 注解扫描器会同时扫到同一个方法——导致工具重复注册、
 * 两套 schema 互相干扰。
 *
 * <p><b>正确做法</b>：业务逻辑抽到 Service，{@code @Tool} 和 {@code @McpTool}
 * 各写一个薄壳。本类就是 MCP 侧的薄壳。
 *
 * <h3>和 @Tool 的对比</h3>
 * <pre>
 * @Tool（进程内）              @McpTool（跨进程）
 * ─────────────────────        ─────────────────────
 * MethodToolCallbackProvider    MCP 注解扫描器
 * 反射直接调用                   JSON-RPC 序列化后调用
 * 同 JVM                        跨 JVM / 跨语言
 * </pre>
 */
@Slf4j
@Component
public class CalculatorMcpTools {

    /** 复用 agent-tools 里的业务逻辑 */
    private final CalculatorTools calculatorTools;

    public CalculatorMcpTools(CalculatorTools calculatorTools) {
        this.calculatorTools = calculatorTools;
    }

    /**
     * MCP 工具：基础四则运算
     *
     * <p>MCP 协议层会自动把方法签名转为 JSON Schema：
     * <pre>
     * {
     *   "name": "calculate",
     *   "description": "执行基本的数学运算，支持加(add)、减(sub)、乘(mul)、除(div)",
     *   "inputSchema": {
     *     "type": "object",
     *     "properties": {
     *       "a":         {"type": "number",  "description": "第一个数字"},
     *       "b":         {"type": "number",  "description": "第二个数字"},
     *       "operation": {"type": "string",  "description": "运算类型"}
     *     },
     *     "required": ["a", "b", "operation"]
     *   }
     * }
     * </pre>
     *
     * @param a         第一个数字
     * @param b         第二个数字
     * @param operation 运算类型：add / sub / mul / div
     * @return 计算结果字符串
     */

    @McpTool(
            name = "mcp_calculate",
            description = "执行基本的数学运算（通过 MCP 远程调用），支持加(add)、减(sub)、乘(mul)、除(div)",
            annotations = @McpTool.McpAnnotations(
                    title = "基础数学运算",
                    readOnlyHint = true,        // ★ 只读——不修改任何状态
                    destructiveHint = false,    // ★ 无破坏性
                    idempotentHint = true,      // ★ 幂等——同参数调用结果相同
                    openWorldHint = false       // ★ 封闭世界——只依赖入参，不访问外部系统
            )
    )
    public String calculate(
            @McpToolParam(description = "第一个数字", required = true) double a,
            @McpToolParam(description = "第二个数字", required = true) double b,
            @McpToolParam(description = "运算类型，只能是 add、sub、mul、div 之一",
                    required = true) String operation) {

        log.info("[MCP-Tool] calculate 被调用: a={}, b={}, op={}", a, b, operation);

        try {
            double result = calculatorTools.calculate(a, b, operation);
            return String.valueOf(result);
        } catch (Exception e) {
            // ★ MCP 工具不应抛异常——返回友好文本让模型理解
            log.warn("[MCP-Tool] calculate 执行失败: {}", e.getMessage());
            return "计算失败：" + e.getMessage();
        }
    }
}