package org.example.workflow.conditional.node.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.workflow.conditional.config.ConditionalWorkflowProperties;
import org.example.workflow.conditional.model.OrderWorkflowState;
import org.example.workflow.conditional.node.AbstractWorkflowNode;
import org.springframework.stereotype.Component;

/**
 * 分支 ③a：待付款 → 发送付款提醒
 */
@Slf4j
@Component
public class PaymentReminderNode extends AbstractWorkflowNode {

    public PaymentReminderNode(ConditionalWorkflowProperties props) {
        super(props);
    }

    @Override
    public String name() {
        return "PaymentReminderNode";
    }

    @Override
    protected String doExecute(OrderWorkflowState state) {
        String contact = state.getOrderInfo().buyerContact();
        String orderId = state.getOrderId();
        double amount = state.getOrderInfo().amount();

        if (props.isDryRun()) {
            log.info("[DryRun] 将发送付款提醒：order={} to={}", orderId, contact);
        } else {
            // 实际发短信/邮件
            log.info("📱 发送付款提醒：order={} to={} amount={}", orderId, contact, amount);
        }

        String result = "已向 " + contact + " 发送付款提醒（金额 " + amount + " 元）";
        state.setBranchResult(result);
        return result;
    }

    /** 发提醒失败——降级为"记录失败，不阻断" */
    @Override
    protected boolean isDegradable() {
        return true;
    }

    @Override
    protected String degradedResult(OrderWorkflowState state) {
        return "付款提醒发送失败，已记录，稍后重试";
    }
}