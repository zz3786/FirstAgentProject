package org.example.core.workflow.conditional.order.fetcher;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.conditional.order.model.OrderBasicInfo;
import org.springframework.stereotype.Component;

/**
 * 订单基础信息——从订单库拉取
 */
@Slf4j
@Component
public class OrderFetcher implements DataFetcher<OrderBasicInfo> {

    @Override
    public String sourceName() {
        return "order-basic";
    }

    @Override
    public OrderBasicInfo fetch(String orderId) {
        sleep(200);

        String status = switch (orderId) {
            case "1001" -> "SHIPPED";
            case "1002" -> "PENDING_PAYMENT";   // ← 触发 HITL
            case "1003" -> "SIGNED";
            case "1004" -> "REFUNDING";         // ← 触发 HITL
            default -> "SHIPPED";
        };

        return new OrderBasicInfo(orderId, status, "示例买家", 199.0);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}