package org.example.workflow.conditional.router;

import lombok.extern.slf4j.Slf4j;
import org.example.workflow.conditional.config.ConditionalWorkflowProperties;
import org.example.workflow.conditional.model.OrderStatus;
import org.example.workflow.conditional.model.OrderWorkflowState;
import org.springframework.stereotype.Component;

/**
 * 条件分支路由器——D59 的核心
 * <p>
 * <b>职责</b>：根据 state 里的中间结果，返回下一个要执行的节点名。
 * <p>
 * <b>设计原则</b>：
 * <ul>
 *   <li>路由规则外置到 yml（routingTable）——业务人员可调整</li>
 *   <li>纯函数——只读 state，不改 state（可观测）</li>
 *   <li>必须有兜底分支——未知状态转人工</li>
 * </ul>
 */
@Slf4j
@Component
public class ConditionalRouter {

    private final ConditionalWorkflowProperties props;

    public ConditionalRouter(ConditionalWorkflowProperties props) {
        this.props = props;
    }

    /**
     * 根据订单状态决定下一个节点
     * <p>
     * <b>分支规则</b>：
     * <pre>
     *   待付款  → PaymentReminderNode
     *   已发货  → LogisticsTrackingNode
     *   已签收  → SatisfactionSurveyNode
     *   其他    → EscalateToHumanNode（兜底）
     * </pre>
     */
    public String route(OrderWorkflowState state) {
        // ① 前置校验
        if (state.getOrderInfo() == null) {
            log.error("[Router] 订单信息为空——无法路由");
            return "EscalateToHumanNode";
        }

        OrderStatus status = state.getOrderInfo().status();

        // ② 查路由表——先读 yml 配置，找不到再走默认规则
        String targetNode = props.getRoutingTable().get(status.name());

        // ③ 兜底规则——代码写死（防止 yml 漏配）
        if (targetNode == null || targetNode.isBlank()) {
            targetNode = switch (status) {
                case PENDING_PAYMENT -> "PaymentReminderNode";
                case SHIPPED         -> "LogisticsTrackingNode";
                case SIGNED          -> "SatisfactionSurveyNode";
                case REFUNDING, REFUNDED -> "EscalateToHumanNode";
                case UNKNOWN -> {
                    if (!props.isEscalateUnknownStatus()) {
                        log.warn("[Router] 遇到未知状态 {}，escalate 已关闭", status);
                        yield "EscalateToHumanNode";   // 仍走转人工，但日志区分
                    }
                    yield "EscalateToHumanNode";
                }
            };
        }

        // ④ 记录决策依据——可观测关键
        log.info("[Router] 决策：status={} → 分支={}", status, targetNode);
        state.setBranchTaken(targetNode);
        return targetNode;
    }
}