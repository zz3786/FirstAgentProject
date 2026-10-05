package org.example.workflow.core.dsl.factory;

import lombok.extern.slf4j.Slf4j;
import org.example.workflow.conditional.order.fetcher.DataFetcher;
import org.example.workflow.conditional.node.WorkflowNode;
import org.example.workflow.core.dsl.exception.DslValidationException;
import org.example.workflow.core.dsl.model.NodeDefinition;
import org.example.workflow.core.parallel.model.ParallelTask;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 动态节点工厂——把 DSL 节点定义转成"运行时对象"
 *
 * <h3>三种产物</h3>
 * <ul>
 *   <li>{@code WorkflowNode}——type=node 时</li>
 *   <li>{@code ParallelTask 列表}——type=parallel 时</li>
 *   <li>{@code DataFetcher 实例}——内部使用</li>
 * </ul>
 *
 * <h3>为什么从 ApplicationContext 取 bean</h3>
 * YAML 里配的是 {@code ref: LoadOrderNode}——是 bean 名。
 * 工厂按名从 Spring 上下文取——实现"YAML 与实现解耦"。
 */
@Slf4j
@Component
public class DynamicNodeFactory {

    private final ApplicationContext ctx;

    public DynamicNodeFactory(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    /**
     * 按 ref 取 WorkflowNode
     */
    public WorkflowNode resolveWorkflowNode(String ref) {
        Object bean = ctx.getBean(ref);
        if (!(bean instanceof WorkflowNode wn)) {
            throw new DslValidationException("<factory>",
                    List.of("bean " + ref + " 不是 WorkflowNode 类型，实际是: "
                            + bean.getClass().getName()));
        }
        return wn;
    }

    /**
     * 按 ref 取 DataFetcher
     */
    @SuppressWarnings("unchecked")
    public <T> DataFetcher<T> resolveDataFetcher(String ref) {
        Object bean = ctx.getBean(ref);
        if (!(bean instanceof DataFetcher<?> df)) {
            throw new DslValidationException("<factory>",
                    List.of("bean " + ref + " 不是 DataFetcher 类型"));
        }
        return (DataFetcher<T>) df;
    }

    /**
     * 构造并行任务
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<ParallelTask<Object>> buildParallelTasks(NodeDefinition node) {
        return node.getTasks().stream().map(t -> {
            DataFetcher<Object> fetcher = (DataFetcher<Object>) resolveDataFetcher(t.getRef());

            java.util.function.Supplier<Object> action = () -> fetcher.fetch("dummy");
            // 注意：实际 orderId 从上下文来——这里简化，运行时替换

            Object fallback = t.getFallback();

            return new ParallelTask<>(
                    t.getName(),
                    action,
                    fallback,
                    t.isCritical(),
                    t.getTimeoutMs() == null ? 0 : t.getTimeoutMs()
            );
        }).toList();
    }
}