package org.example.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OrderTools {

    /**
     * 内存模拟订单数据（D69：加入租户测试数据）
     * <p>
     * 订单号编码规则（纯数字，便于 LLM 原样传递）：
     * <ul>
     *   <li>{@code 1001} ~ {@code 1004}——公共订单（4 位）</li>
     *   <li>{@code 10001} / {@code 10002}——hospital-a 的订单（5 位，首位 1）</li>
     *   <li>{@code 20001} / {@code 20002}——hospital-b 的订单（5 位，首位 2）</li>
     * </ul>
     */
    private static final Map<String, String> ORDER_DB = new ConcurrentHashMap<>();

    static {
        // ── 公共订单（兼容旧测试）
        ORDER_DB.put("1001", "已发货，预计2026-09-20送达");
        ORDER_DB.put("1002", "待付款，请及时支付");
        ORDER_DB.put("1003", "已签收，交易完成");
        ORDER_DB.put("1004", "退款中，预计3个工作日到账");

        // ── hospital-a 的订单
        ORDER_DB.put("10001", "已发货，预计2026-09-20送达（hospital-a）");
        ORDER_DB.put("10002", "待付款（hospital-a）");
        ORDER_DB.put("A10001", "AAA，预计2026-09-20送达（hospital-a）");

        // ── hospital-b 的订单
        ORDER_DB.put("20001", "待收货（hospital-b）");
        ORDER_DB.put("20002", "已签收（hospital-b）");
        ORDER_DB.put("B10001", "DDD（hospital-b）");
    }

    @Tool(description = "根据订单号查询订单状态。仅用于查询状态，不处理退换货、催单等其他操作。" +
            "如果用户询问退换货、催单等，不要调用此工具。")
    public String getOrderStatus(
            @ToolParam(description = "订单号。用户输入什么就传什么，不要增加、删除或修改任何字符。",
                    required = true)
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