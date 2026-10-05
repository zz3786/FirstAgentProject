package org.example.workflow.core.dsl.parser;

import lombok.extern.slf4j.Slf4j;
import org.example.workflow.core.dsl.exception.DslValidationException;
import org.example.workflow.core.dsl.model.NodeDefinition;
import org.example.workflow.core.dsl.model.WorkflowDefinition;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工作流静态校验器
 *
 * <h3>校验项</h3>
 * <ol>
 *   <li>id 非空、唯一</li>
 *   <li>flow 里的节点都存在</li>
 *   <li>每个节点 type 合法</li>
 *   <li>type=node 必须有 ref；type=switch 必须有 on + cases；type=parallel 必须有 tasks</li>
 *   <li>switch 的 case 目标节点存在</li>
 *   <li>无循环依赖（简化版——flow 是有序列表，天然无环）</li>
 *   <li>节点 id 全局唯一</li>
 * </ol>
 */
@Slf4j
@Component
public class WorkflowValidator {

    private static final Set<String> VALID_TYPES = Set.of(
            "node", "switch", "parallel", "hitl", "end"
    );

    /**
     * 校验，不通过抛异常
     */
    public void validate(WorkflowDefinition def) {
        List<String> errors = new ArrayList<>();

        // ① 顶层字段
        if (def.getId() == null || def.getId().isBlank()) {
            errors.add("workflow.id 不能为空");
        }
        if (def.getNodes() == null || def.getNodes().isEmpty()) {
            errors.add("workflow.nodes 不能为空");
        }
        if (def.getFlow() == null || def.getFlow().isEmpty()) {
            errors.add("workflow.flow 不能为空");
        }

        if (!errors.isEmpty()) {
            throw new DslValidationException(def.getId(), errors);
        }

        // ② 节点 id 唯一
        Set<String> nodeIds = new HashSet<>();
        for (NodeDefinition n : def.getNodes()) {
            if (n.getId() == null || n.getId().isBlank()) {
                errors.add("节点 id 不能为空");
                continue;
            }
            if (!nodeIds.add(n.getId())) {
                errors.add("节点 id 重复: " + n.getId());
            }
        }

        // ③ flow 里的节点必须存在
        for (String id : def.getFlow()) {
            if (!nodeIds.contains(id)) {
                errors.add("flow 引用了不存在的节点: " + id);
            }
        }

        // ④ 每个节点按类型校验
        for (NodeDefinition n : def.getNodes()) {
            validateNode(n, nodeIds, errors);
        }

        if (!errors.isEmpty()) {
            throw new DslValidationException(def.getId(), errors);
        }

        log.info("✅ 工作流 [{}] 校验通过", def.getId());
    }

    private void validateNode(NodeDefinition n, Set<String> allIds, List<String> errors) {
        String id = n.getId();

        if (n.getType() == null || !VALID_TYPES.contains(n.getType())) {
            errors.add("节点 " + id + " 的 type 非法: " + n.getType()
                    + "（允许： " + VALID_TYPES + "）");
            return;
        }

        switch (n.getType()) {
            case "node" -> {
                if (n.getRef() == null || n.getRef().isBlank()) {
                    errors.add("节点 " + id + " (type=node) 缺少 ref");
                }
            }
            case "switch" -> {
                if (n.getOn() == null || n.getOn().isBlank()) {
                    errors.add("节点 " + id + " (type=switch) 缺少 on");
                }
                if (n.getCases() == null || n.getCases().isEmpty()) {
                    errors.add("节点 " + id + " (type=switch) 缺少 cases");
                } else {
                    for (Map.Entry<String, String> e : n.getCases().entrySet()) {
                        if (!allIds.contains(e.getValue())) {
                            errors.add("节点 " + id + " 的 case[" + e.getKey()
                                    + "] 目标不存在: " + e.getValue());
                        }
                    }
                }
                if (n.getDefaultBranch() != null && !allIds.contains(n.getDefaultBranch())) {
                    errors.add("节点 " + id + " 的 default 目标不存在: " + n.getDefaultBranch());
                }
            }
            case "parallel" -> {
                if (n.getTasks() == null || n.getTasks().isEmpty()) {
                    errors.add("节点 " + id + " (type=parallel) 缺少 tasks");
                } else {
                    for (NodeDefinition.ParallelTaskDef t : n.getTasks()) {
                        if (t.getName() == null || t.getRef() == null) {
                            errors.add("节点 " + id + " 的 task 缺 name 或 ref");
                        }
                    }
                }
            }
            case "hitl" -> {
                // when 可以为空——不填表示总是触发
            }
            case "end" -> {
                // 结束节点——无必填字段
            }
        }
    }
}