package org.example.core.workflow.core.registry;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.core.node.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 节点注册表——按名字取节点
 * <p>
 * <b>为什么注入 List 而不是 Map</b>：
 * Spring 对 {@code Map<String, T>} 有"集合注入"特殊规则——key 会被替换成 bean name，
 * 导致我们自定义的 {@code WorkflowNode::name} 作为 key 失效。
 * 改为注入 List，手动组装 Map——key 完全由我们掌控。
 */
@Slf4j
@Component
public class WorkflowNodeRegistry {

    private final Map<String, WorkflowNode> nodes;

    public WorkflowNodeRegistry(List<WorkflowNode> nodeList) {
        // ★ 手动组装：key = WorkflowNode.name()，不是 bean name
        this.nodes = nodeList.stream()
                .collect(Collectors.toMap(
                        WorkflowNode::name,
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "节点名重复: " + a.name() + " 与 " + b.name());
                        }
                ));
        log.info("WorkflowNodeRegistry 初始化：{} 个节点 -> {}",
                nodes.size(), nodes.keySet());
    }

    public WorkflowNode get(String name) {
        WorkflowNode node = nodes.get(name);
        if (node == null) {
            throw new IllegalArgumentException("未注册的节点: " + name
                    + "，已注册: " + nodes.keySet());
        }
        return node;
    }

    public boolean exists(String name) {
        return nodes.containsKey(name);
    }
}