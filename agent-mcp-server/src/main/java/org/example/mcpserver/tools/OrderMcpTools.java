package org.example.mcpserver.tools;

import lombok.extern.slf4j.Slf4j;
import org.example.tools.OrderTools;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP 订单工具（D65）
 *
 * <h3>和 CalculatorMcpTools 的区别</h3>
 * <p>
 * 本类展示了如何从 MCP 请求上下文中获取信息。
 * MCP 协议支持在工具方法中注入特殊参数类型，
 * 这些参数会自动注入并从 JSON Schema 中排除[reference:4]。
 *
 * <h3>当前版本说明</h3>
 * <p>
 * Spring AI 1.1.8 的 {@code McpSyncRequestContext} 提供了
 * 统一的请求上下文访问接口。D69 做 RBAC 时会用它读取用户身份。
 * 本类暂不注入上下文，保持最简形态。
 */
@Slf4j
@Component
public class OrderMcpTools {

    private final OrderTools orderTools;

    public OrderMcpTools(OrderTools orderTools) {
        this.orderTools = orderTools;
    }

    /**
     * MCP 工具：查询订单状态
     *
     * <p>这是最典型的“跨进程工具”场景：
     * 订单数据在别的系统里，Agent 通过 MCP 远程调用。
     * 生产环境中，这个工具可能部署在订单服务所在的网络区域，
     * 通过 MCP 协议对外暴露——而不是把订单库密码塞进 Agent 进程。
     *
     * @param orderId 订单号（纯数字）
     * @return 订单状态描述
     */
    @McpTool(
            name = "mcp_getOrderStatus",
            description = "根据订单号查询订单状态。仅用于查询状态，不处理退换货、催单等其他操作。",
            annotations = @McpTool.McpAnnotations(
                    title = "查询订单状态",
                    readOnlyHint = true,        // ★ 只读
                    destructiveHint = false,    // ★ 无破坏性
                    idempotentHint = true,      // ★ 幂等——查两次结果一样
                    openWorldHint = true        // ★ 开放世界——访问了订单系统（外部资源）
            )
    )
    public String getOrderStatus(
            @McpToolParam(description = "订单号，纯数字格式，例如 1001",
                    required = true) String orderId) {

        log.info("[MCP-Tool] getOrderStatus 被调用: orderId={}", orderId);

        // 参数校验——MCP 工具必须对入参做防御性检查
        if (orderId == null || orderId.isBlank()) {
            return "订单号不能为空";
        }
        if (!orderId.matches("\\d+")) {
            return "订单号格式不正确，应为纯数字";
        }

        return orderTools.getOrderStatus(orderId);
    }
}