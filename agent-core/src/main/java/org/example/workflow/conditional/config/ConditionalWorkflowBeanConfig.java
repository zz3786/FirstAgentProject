package org.example.workflow.conditional.config;

import lombok.extern.slf4j.Slf4j;
import org.example.workflow.conditional.node.WorkflowNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 条件工作流 Bean 装配
 */
@Slf4j
@Configuration
public class ConditionalWorkflowBeanConfig {

    /**
     * 节点注册表——name → node
     * <p>
     * Spring 自动把同一接口的所有实现注入到 List 里——
     * 无需手动 new。
     */
    @Bean
    public Map<String, WorkflowNode> workflowNodeMap(List<WorkflowNode> nodes) {
        Map<String, WorkflowNode> map = nodes.stream()
                .collect(Collectors.toMap(WorkflowNode::name, n -> n));

        log.info("条件工作流节点注册完成：共 {} 个节点 -> {}",
                map.size(), map.keySet());
        return map;
    }
}