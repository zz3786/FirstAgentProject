package org.example.workflow.conditional.node;

import org.example.workflow.conditional.model.OrderWorkflowState;
import org.example.workflow.conditional.model.NodeResult;

/**
 * 工作流节点接口
 * <p>
 * 两个方法：
 * <ul>
 *   <li>shouldEnter——是否进入该节点（条件判断，纯函数）</li>
 *   <li>execute——执行节点逻辑（可能有副作用）</li>
 * </ul>
 * 分离的好处：条件可以独立测试，不用 mock 执行部分。
 */
public interface WorkflowNode {

    /** 节点名——全局唯一，用于日志和注册 */
    String name();

    /**
     * 是否进入该节点
     * <p>
     * 默认 true（普通节点）。决策类节点会重写这个判断。
     */
    default boolean shouldEnter(OrderWorkflowState state) {
        return true;
    }

    /**
     * 执行节点
     *
     * @throws org.example.workflow.conditional.exception.WorkflowNodeException
     */
    NodeResult execute(OrderWorkflowState state);
}