package org.example.core.workflow.core.node;

import lombok.extern.slf4j.Slf4j;
import org.example.common.utils.TextUtils;
import org.example.core.workflow.conditional.order.config.OrderWorkflowProperties;
import org.example.core.workflow.core.config.WorkflowEngineProperties;
import org.example.core.workflow.core.exception.WorkflowNodeException;
import org.example.core.workflow.core.model.NodeResult;
import org.example.core.workflow.conditional.order.model.OrderWorkflowState;
import org.example.core.workflow.core.model.WorkflowTrace;

/**
 * 节点抽象基类——模板方法
 * <p>
 * 把"日志 + 计时 + trace + 异常兜底"统一处理，
 * 子类只实现 doExecute 的业务逻辑。
 */
@Slf4j
public abstract class AbstractWorkflowNode implements WorkflowNode {

    protected final WorkflowEngineProperties props;

    protected AbstractWorkflowNode(WorkflowEngineProperties props) {
        this.props = props;
    }

    /**
     * 模板方法——统一的执行骨架
     * <p>
     * final 防止子类覆盖——保证所有节点都有同样的日志/计时/trace 行为。
     */
    @Override
    public final NodeResult execute(OrderWorkflowState state) {
        long start = System.currentTimeMillis();
        String name = name();

        // ① 是否进入
        if (!shouldEnter(state)) {
            log.debug("[{}] 跳过（条件不满足）", name);
            state.addTrace(WorkflowTrace.skipped(name, "shouldEnter=false"));
            return NodeResult.success(name, "SKIPPED", 0);
        }

        log.info("[{}] ▶️ 开始执行", name);

        try {
            // ② 执行业务逻辑（子类实现）
            String output = doExecute(state);

            long cost = System.currentTimeMillis() - start;

            log.info("[{}] ✅ 完成 costMs={}", name, cost);
            state.addTrace(WorkflowTrace.success(name, cost, TextUtils.truncate(output, 200)));
            return NodeResult.success(name, output, cost);

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[{}] ❌ 失败 costMs={} err={}", name, cost, e.getMessage(), e);
            // ③ 是否可降级
            if (isDegradable()) {
                String fallback = degradedResult(state);
                state.addTrace(WorkflowTrace.success(name, cost,
                        "降级：" + fallback));
                return NodeResult.success(name, fallback, cost);
            }
            state.addTrace(WorkflowTrace.failed(name, cost, e.getMessage()));
            throw new WorkflowNodeException(name, e.getMessage(), e);
        }
    }

    /** 子类实现——真正的业务逻辑，返回输出的字符串描述 */
    protected abstract String doExecute(OrderWorkflowState state);

    /** 是否可降级——默认否 */
    protected boolean isDegradable() {
        return false;
    }

    /** 降级结果——子类覆盖 */
    protected String degradedResult(OrderWorkflowState state) {
        return "（该步骤未能完成，已跳过）";
    }

}