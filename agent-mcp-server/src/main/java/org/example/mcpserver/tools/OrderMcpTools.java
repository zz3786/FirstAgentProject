package org.example.mcpserver.tools;

import lombok.extern.slf4j.Slf4j;
import org.example.tools.OrderTools;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP 订单工具（D65 / D69 更新）
 */
@Slf4j
@Component
public class OrderMcpTools {

    private final OrderTools orderTools;

    public OrderMcpTools(OrderTools orderTools) {
        this.orderTools = orderTools;
    }

    @McpTool(
            name = "mcp_getOrderStatus",
            description = "根据订单号查询订单状态。仅用于查询状态，不处理退换货、催单等其他操作。"
    )
    public String getOrderStatus(
            @McpToolParam(description = "订单号。用户输入什么就传什么，不要增加、删除或修改任何字符。",
                    required = true)
            String orderId) {

        log.info("[MCP-Tool] getOrderStatus 被调用: orderId={}", orderId);

        if (orderId == null || orderId.isBlank()) {
            return "订单号不能为空";
        }
        // ★ D69：改为"纯数字"校验（不再限制必须纯数字——因为我们用 5 位数字编码租户）
        if (!orderId.matches("\\d+")) {
            return "订单号格式不正确，应为纯数字";
        }

        return orderTools.getOrderStatus(orderId);
    }
}