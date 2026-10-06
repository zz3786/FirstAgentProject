package org.example.core.workflow.core.dsl.factory;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.conditional.order.fetcher.DataFetcher;
import org.example.core.workflow.core.dsl.exception.DslValidationException;
import org.example.core.workflow.core.dsl.model.NodeDefinition;
import org.example.core.workflow.core.node.WorkflowNode;
import org.example.core.workflow.core.parallel.model.ParallelTask;
import org.example.core.workflow.core.registry.WorkflowNodeRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 动态节点工厂
 * <p>
 * <b>关键：不用 ctx.getBean(ref)</b>
 * YAML 里 ref 写的是"类名"（DecisionNode），但 Spring Bean 名是"首字母小写"（decisionNode）。
 * 直接 getBean 会 NoSuchBeanDefinition。
 * <p>
 * 改为：
 * <ul>
 *   <li>WorkflowNode → 从 WorkflowNodeRegistry 按 name() 查</li>
 *   <li>DataFetcher → 按类名（getSimpleName()）索引后查</li>
 * </ul>
 */
@Slf4j
@Component
public class DynamicNodeFactory {

    private final WorkflowNodeRegistry nodeRegistry;

    /** DataFetcher 按类名索引——YAML 里写的就是类名 */
    private final Map<String, DataFetcher<?>> fetcherByClassName;

    public DynamicNodeFactory(WorkflowNodeRegistry nodeRegistry,
                              List<DataFetcher<?>> fetchers) {
        this.nodeRegistry = nodeRegistry;

        this.fetcherByClassName = fetchers.stream()
                .collect(Collectors.toMap(
                        f -> f.getClass().getSimpleName(),
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "DataFetcher 类名重复: " + a.getClass().getName()
                                            + " 与 " + b.getClass().getName());
                        }
                ));

        log.info("[DynamicNodeFactory] 初始化：{} 个 fetcher -> {}",
                fetcherByClassName.size(), fetcherByClassName.keySet());
    }

    /**
     * 按 ref 取 WorkflowNode——从 Registry 查
     */
    public WorkflowNode resolveWorkflowNode(String ref) {
        if (!nodeRegistry.exists(ref)) {
            throw new DslValidationException("<factory>",
                    List.of("节点不存在: " + ref
                            + "，已注册节点: " + nodeRegistry.listNames()));
        }
        return nodeRegistry.get(ref);
    }

    /**
     * 按 ref 取 DataFetcher——从按类名索引的 Map 查
     */
    @SuppressWarnings("unchecked")
    public <T> DataFetcher<T> resolveDataFetcher(String ref) {
        DataFetcher<?> fetcher = fetcherByClassName.get(ref);
        if (fetcher == null) {
            throw new DslValidationException("<factory>",
                    List.of("DataFetcher 不存在: " + ref
                            + "，已注册: " + fetcherByClassName.keySet()));
        }
        return (DataFetcher<T>) fetcher;
    }

    /**
     * 构造并行任务列表
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<ParallelTask<Object>> buildParallelTasks(NodeDefinition node) {
        return node.getTasks().stream().map(t -> {
            DataFetcher<Object> fetcher = (DataFetcher<Object>) resolveDataFetcher(t.getRef());

            java.util.function.Supplier<Object> action = () -> fetcher.fetch("dummy");
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