package org.example.core.workflow.conditional.order.nodes;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.conditional.order.model.OrderWorkflowState;
import org.example.core.workflow.core.config.WorkflowEngineProperties;
import org.example.core.workflow.core.node.AbstractWorkflowNode;
import org.springframework.stereotype.Component;

/**
 * 分支 ③c：已签收 → 发送满意度调查
 */
@Slf4j
@Component
public class SatisfactionSurveyNode extends AbstractWorkflowNode {

    public SatisfactionSurveyNode(WorkflowEngineProperties props) {
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