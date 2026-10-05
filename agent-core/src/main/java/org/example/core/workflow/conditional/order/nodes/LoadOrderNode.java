package org.example.core.workflow.conditional.order.nodes;

import lombok.extern.slf4j.Slf4j;
import org.example.tools.OrderTools;
import org.example.core.workflow.conditional.order.config.OrderWorkflowProperties;
import org.example.core.workflow.conditional.order.model.OrderInfo;
import org.example.core.workflow.conditional.order.model.OrderStatus;
import org.example.core.workflow.conditional.order.model.OrderWorkflowState;
import org.example.core.workflow.core.node.AbstractWorkflowNode;
import org.springframework.stereotype.Component;

/**
 * 步骤 ①：查询订单
 * <p>
 * 输入：state.orderId
 * 输出：state.orderInfo
 * 失败策略：不可降级——查不到订单没法继续
 */
@Slf4j
@Component
public class LoadOrderNode extends AbstractWorkflowNode {

    private final OrderTools orderTools;

    public LoadOrderNode(OrderWorkflowProperties props, OrderTools orderTools) {
        super(props);
        this.orderTools = orderTools;
    }

    @Override
    public String name() {
        return "LoadOrderNode";
    }

    @Override
    protected String doExecute(OrderWorkflowState state) {
        String orderId = state.getOrderId();

        // ① 调工具查询（此处是 mock，实际会有真实 IO）
        String rawStatus = orderTools.getOrderStatus(orderId);
        if (rawStatus == null || rawStatus.contains("未找到")) {
            throw new IllegalStateException("订单不存在: " + orderId);
        }

        // ② 解析成结构化模型
        OrderStatus status = parseStatus(rawStatus);
        OrderInfo info = new OrderInfo(
                orderId,
                status,
                "示例买家",       // 实际从 DB 查
                "13800000000",
                199.0
        );
        state.setOrderInfo(info);

        return "订单 " + orderId + " 状态：" + status.getLabel();
    }

    /** 从工具返回的字符串里解析状态 */
    private OrderStatus parseStatus(String raw) {
        if (raw.contains("待付款")) return OrderStatus.PENDING_PAYMENT;
        if (raw.contains("已发货")) return OrderStatus.SHIPPED;
        if (raw.contains("已签收")) return OrderStatus.SIGNED;
        if (raw.contains("退款中")) return OrderStatus.REFUNDING;
        if (raw.contains("已退款")) return OrderStatus.REFUNDED;
        return OrderStatus.UNKNOWN;
    }
}