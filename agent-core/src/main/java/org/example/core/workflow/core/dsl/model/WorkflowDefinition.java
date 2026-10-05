package org.example.core.workflow.core.dsl.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流定义——DSL 的顶层结构
 * <p>
 * <b>不可变设计</b>：解析后不再修改——保证并发安全。
 * 用 @Data 是为了 Jackson 反序列化——业务逻辑不改它。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowDefinition {

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("version")
    private String version = "1.0.0";

    @JsonProperty("description")
    private String description;

    /** 全局默认配置——每个节点可覆盖 */
    @JsonProperty("defaults")
    private Defaults defaults = new Defaults();

    /** 全局变量——表达式里可引用 */
    @JsonProperty("variables")
    private Map<String, Object> variables = new HashMap<>();

    /** 节点定义列表 */
    @JsonProperty("nodes")
    private List<NodeDefinition> nodes = new ArrayList<>();

    /** 执行流——节点 id 的有序列表 */
    @JsonProperty("flow")
    private List<String> flow = new ArrayList<>();

    /** 按 id 索引节点 */
    public NodeDefinition nodeById(String nodeId) {
        return nodes.stream()
                .filter(n -> nodeId.equals(n.getId()))
                .findFirst()
                .orElse(null);
    }

    /** 全局默认配置 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Defaults {
        @JsonProperty("timeout-ms")
        private Long timeoutMs;

        @JsonProperty("retry")
        private RetryConfig retry;
    }
}