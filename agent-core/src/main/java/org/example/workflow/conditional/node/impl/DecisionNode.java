package org.example.workflow.conditional.node.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.workflow.conditional.config.ConditionalWorkflowProperties;
import org.example.workflow.conditional.model.OrderWorkflowState;
import org.example.workflow.conditional.node.AbstractWorkflowNode;
import org.example.workflow.conditional.router.ConditionalRouter;
import org.springframework.stereotype.Component;

/**
 * 步骤 ②：决策节点
 * <p>
 * 本身不执行业务——只是记录"路由决策"，真正的路由由 OrderConditionalWorkflow 调用 ConditionalRouter 完成。
 * <p>
 * 这个节点的价值：
 * <ul>
 *   <li>把"决策"作为一个显式步骤——可观测</li>
 *   <li>在 trace 里留下"决策依据"</li>
 *   <li>后续审计时能看到"为什么走了这条分支"</li>
 * </ul>
 */
@Slf4j
@Component
public class DecisionNode extends AbstractWorkflowNode {

    private final ConditionalRouter router;

    public DecisionNode(ConditionalWorkflowProperties props, ConditionalRouter router) {
        super(props);
        this.router = router;
    }

    @Override
    public String name() {
        return "DecisionNode";
    }

    @Override
    protected String doExecute(OrderWorkflowState state) {
        // 调用路由器——决策结果写入 state.branchTaken
        String branch = router.route(state);
        return "决策分支：" + branch + "（依据：status="
                + state.getOrderInfo().status() + "）";
    }
}