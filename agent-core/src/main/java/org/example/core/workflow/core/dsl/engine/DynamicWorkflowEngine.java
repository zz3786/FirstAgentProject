package org.example.core.workflow.core.dsl.engine;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.conditional.order.model.OrderWorkflowState;
import org.example.core.workflow.core.node.WorkflowNode;
import org.example.core.workflow.core.dsl.expression.ExpressionEvaluator;
import org.example.core.workflow.core.dsl.factory.DynamicNodeFactory;
import org.example.core.workflow.core.dsl.model.NodeDefinition;
import org.example.core.workflow.core.dsl.model.WorkflowDefinition;
import org.example.core.workflow.core.dsl.registry.WorkflowDefinitionRegistry;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态工作流引擎——按 DSL 定义执行
 *
 * <h3>执行流程</h3>
 * <pre>
 *   for (nodeId : flow):
 *       node = def.nodeById(nodeId)
 *       switch node.type:
 *           case "node":
 *               workNode = factory.resolveWorkflowNode(node.ref)
 *               workNode.execute(state)
 *           case "switch":
 *               targetId = evaluateSwitch(node, state)
 *               jumpTo(targetId)  // 改变下一步
 *           case "parallel":
 *               用 ParallelExecutor 并行执行 tasks
 *           case "hitl":
 *               if (when == true): 创建 HITL 任务 → 抛 WorkflowSuspendedException
 *               else: 跳过
 *           case "end": 结束
 * </pre>
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li>switch 节点是唯一会"改变流程"的节点——返回跳转目标</li>
 *   <li>除 switch 外，其他节点执行完顺序推进</li>
 *   <li>变量注入到 state 里——所有表达式都能引用</li>
 * </ul>
 */
@Slf4j
@Component
public class DynamicWorkflowEngine {

    private final WorkflowDefinitionRegistry registry;
    private final DynamicNodeFactory nodeFactory;
    private final ExpressionEvaluator expressionEvaluator;

    public DynamicWorkflowEngine(WorkflowDefinitionRegistry registry,
                                 DynamicNodeFactory nodeFactory,
                                 ExpressionEvaluator expressionEvaluator) {
        this.registry = registry;
        this.nodeFactory = nodeFactory;
        this.expressionEvaluator = expressionEvaluator;
    }

    /**
     * 执行工作流
     */
    public void execute(String workflowId, OrderWorkflowState state) {
        WorkflowDefinition def = registry.get(workflowId);
        if (def == null) {
            throw new IllegalArgumentException("工作流未注册: " + workflowId);
        }

        log.info("▶️ DSL 工作流启动: id={}, version={}", workflowId, def.getVersion());

        // 流程调度器——支持"跳转"
        List<String> flow = def.getFlow();
        int pc = 0;   // program counter

        while (pc < flow.size()) {
            String nodeId = flow.get(pc);
            NodeDefinition node = def.nodeById(nodeId);
            if (node == null) {
                throw new IllegalStateException("节点不存在: " + nodeId);
            }

            // 超时检查
            if (state.isTimeout()) {
                log.warn("工作流整体超时: {}", workflowId);
                state.setStatus("TIMEOUT");
                return;
            }

            // 执行节点
            String jump = executeNode(def, node, state);

            // 处理跳转
            if (jump != null) {
                int targetPc = flow.indexOf(jump);
                if (targetPc < 0) {
                    log.warn("跳转目标不在 flow 中: {}", jump);
                    throw new IllegalStateException("跳转目标不存在: " + jump);
                }
                pc = targetPc;
            } else {
                pc++;
            }
        }

        log.info("✅ DSL 工作流执行完成: id={}", workflowId);
    }

    /**
     * 执行单个节点
     *
     * @return 跳转目标节点 id（仅 switch 会返回非空）；null 表示顺序推进
     */
    private String executeNode(WorkflowDefinition def,
                               NodeDefinition node,
                               OrderWorkflowState state) {
        log.info("▶️ [DSL] 执行节点: id={}, type={}", node.getId(), node.getType());

        // 构造变量——供表达式使用
        Map<String, Object> vars = buildVariables(def, state);

        return switch (node.getType()) {
            case "node" -> {
                WorkflowNode wn = nodeFactory.resolveWorkflowNode(node.getRef());
                wn.execute(state);
                yield null;
            }
            case "switch" -> {
                String target = evaluateSwitch(node, vars);
                log.info("[DSL] switch 决策: {} → {}", node.getOn(), target);
                // 把 switch 结果写入 state——便于后续引用
                state.setBranchTaken(target);
                yield target;
            }
            case "parallel" -> {
                // 简化实现——完整版参考 D60
                log.info("[DSL] parallel 节点——生产中由 ParallelExecutor 执行");
                yield null;
            }
            case "hitl" -> {
                // 判断是否触发
                if (node.getWhen() == null|| expressionEvaluator.evaluateBoolean(node.getWhen(), vars)) {
                    log.warn("[DSL] HITL 触发: id={}", node.getId());
                    // 实际实现：创建 HITL 任务 + 抛 WorkflowSuspendedException
                    // 简化：打日志
                }
                yield null;
            }
            case "end" -> null;
            default -> throw new IllegalStateException("未知节点类型: " + node.getType());
        };
    }

    /** switch 求值 */
    private String evaluateSwitch(NodeDefinition node, Map<String, Object> vars) {
        Object onValue = expressionEvaluator.evaluateObject(node.getOn(), vars);
        String key = onValue == null ? "" : onValue.toString();

        if (node.getCases() != null && node.getCases().containsKey(key)) {
            return node.getCases().get(key);
        }
        return node.getDefaultBranch();
    }

    /** 构造表达式变量上下文 */
    private Map<String, Object> buildVariables(WorkflowDefinition def,
                                               OrderWorkflowState state) {
        Map<String, Object> vars = new HashMap<>();

        // 全局变量
        if (def.getVariables() != null) {
            vars.putAll(def.getVariables());
        }

        // 从 state 里提取业务字段
        vars.put("orderId", state.getOrderId());
        vars.put("fullUserId", state.getFullUserId());
        vars.put("executionId", state.getExecutionId());
        vars.put("branchTaken", state.getBranchTaken());

        if (state.getOrderInfo() != null) {
            vars.put("status", state.getOrderInfo().status().name());
            vars.put("amount", state.getOrderInfo().amount());
            vars.put("buyerName", state.getOrderInfo().buyerName());
        }

        return vars;
    }
}