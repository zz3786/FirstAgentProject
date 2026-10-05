package org.example.core.workflow.conditional.order.nodes;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.conditional.order.model.OrderWorkflowState;
import org.example.core.workflow.core.config.WorkflowEngineProperties;
import org.example.core.workflow.core.node.AbstractWorkflowNode;
import org.springframework.stereotype.Component;

/**
 * 分支 ③b：已发货 → 发送物流追踪
 */
@Slf4j
@Component
public class LogisticsTrackingNode extends AbstractWorkflowNode {

    public LogisticsTrackingNode(WorkflowEngineProperties props) {
        super(props);
    }

    @Override
    public String name() {
        return "LogisticsTrackingNode";
    }

    @Override
    protected String doExecute(OrderWorkflowState state) {
        String orderId = state.getOrderId();
        // 模拟查询物流接口
        String trackingInfo = "快递单号 SF" + System.currentTimeMillis() % 1000000
                + "，预计明天下午送达";

        log.info("🚚 物流追踪：order={} → {}", orderId, trackingInfo);
        String result = "订单 " + orderId + " 物流信息：" + trackingInfo;
        state.setBranchResult(result);
        return result;
    }
}