package org.example.workflow.conditional.node.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.workflow.conditional.config.ConditionalWorkflowProperties;
import org.example.workflow.conditional.model.OrderWorkflowState;
import org.example.workflow.conditional.node.AbstractWorkflowNode;
import org.springframework.stereotype.Component;

/**
 * 兜底分支 ③d：未知/复杂状态 → 转人工
 * <p>
 * 这是"必须有兜底"原则的体现——任何未处理的状态都不能静默失败。
 */
@Slf4j
@Component
public class EscalateToHumanNode extends AbstractWorkflowNode {

    public EscalateToHumanNode(ConditionalWorkflowProperties props) {
        super(props);
    }

    @Override
    public String name() {
        return "EscalateToHumanNode";
    }

    @Override
    protected String doExecute(OrderWorkflowState state) {
        String orderId = state.getOrderId();
        String status = state.getOrderInfo() == null
                ? "未知" : state.getOrderInfo().status().getLabel();

        log.warn("⚠️ 转人工处理：order={} status={} user={}",
                orderId, status, state.getFullUserId());

        // 生产环境：推工单系统、发告警群、发邮件
        String ticketId = "HUMAN-" + System.currentTimeMillis();
        String result = "订单 " + orderId + " 状态「" + status
                + "」需要人工处理，已创建工单 " + ticketId;

        state.setBranchResult(result);
        return result;
    }
}