package org.example.workflow.core.hitl.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 挂起时保存的工作流上下文快照
 * <p>
 * <b>必须可序列化</b>——存到 Redis，人工决策后从 Redis 恢复。
 * <p>
 * <b>为什么不直接存 OrderWorkflowState</b>：
 * State 里有 final 字段、复杂对象引用——Jackson 反序列化会有问题。
 * 用独立的 DTO 层，只存"恢复执行必需"的字段。
 */
@Data
@NoArgsConstructor
public class HitlContext {

    /** 工作流类型——如 "order-workflow"，用于路由到对应的恢复逻辑 */
    private String workflowType;

    /** 业务 ID——如订单号 */
    private String workflowId;

    /** 执行实例 ID */
    private String executionId;

    /** 用户信息 */
    private String fullUserId;
    private String conversationId;

    /** 停在哪个节点之后 */
    private String currentStep;

    /** 已完成节点名列表 */
    private List<String> completedNodes;

    /** 待人工确认的动作描述 */
    private String pendingAction;

    /** 后续要执行的节点名列表 */
    private List<String> remainingNodes;

    /** 业务数据快照——key 由各工作流自定义 */
    private Map<String, Object> payload = new HashMap<>();

    /** 原始开始时间 */
    private long workflowStartTime;

    /** 原始截止时间（工作流整体超时） */
    private long workflowDeadline;
}