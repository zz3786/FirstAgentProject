package org.example.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OrderTools {

    // 内存模拟订单数据
    private static final Map<String, String> ORDER_DB = new ConcurrentHashMap<>();

    static {
        ORDER_DB.put("1001", "已发货，预计2026-09-20送达");
        ORDER_DB.put("1002", "待付款，请及时支付");
        ORDER_DB.put("1003", "已签收，交易完成");
        ORDER_DB.put("1004", "退款中，预计3个工作日到账");
    }

    @Tool(description = "根据订单号查询订单状态。仅用于查询状态，不处理退换货、催单等其他操作。" +
            "如果用户询问退换货、催单等，不要调用此工具。")
    public String getOrderStatus(
            @ToolParam(description = "订单号，纯数字格式，例如 1001", required = true)
            String orderId) {

        // 模拟延迟（展示真实场景）
        try { Thread.sleep(100); } catch (InterruptedException e) { /* ignore */ }

        String status = ORDER_DB.get(orderId);
        if (status == null) {
            return "未找到订单号 " + orderId + "，请确认订单号是否正确";
        }
        return "订单 " + orderId + " 状态：" + status;
    }

}
