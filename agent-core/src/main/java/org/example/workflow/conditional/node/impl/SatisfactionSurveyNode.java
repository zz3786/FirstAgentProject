package org.example.workflow.conditional.node.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.workflow.conditional.config.ConditionalWorkflowProperties;
import org.example.workflow.conditional.model.OrderWorkflowState;
import org.example.workflow.conditional.node.AbstractWorkflowNode;
import org.springframework.stereotype.Component;

/**
 * 分支 ③c：已签收 → 发送满意度调查
 */
@Slf4j
@Component
public class SatisfactionSurveyNode extends AbstractWorkflowNode {

    public SatisfactionSurveyNode(ConditionalWorkflowProperties props) {
        super(props);
    }

    @Override
    public String name() {
        return "SatisfactionSurveyNode";
    }

    @Override
    protected String doExecute(OrderWorkflowState state) {
        String orderId = state.getOrderId();
        String surveyUrl = "https://survey.example.com/o/" + orderId;

        log.info("📝 发送满意度调查：order={} url={}", orderId, surveyUrl);
        String result = "已向买家发送满意度调查链接：" + surveyUrl;
        state.setBranchResult(result);
        return result;
    }
}