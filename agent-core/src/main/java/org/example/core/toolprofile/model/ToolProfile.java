package org.example.core.toolprofile.model;

import lombok.Data;
import org.example.toolregistry.model.ToolSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个工具画像（Tool Profile）
 *
 * <h3>语义</h3>
 * <p>
 * 一个 Profile 描述"某个业务场景需要哪些工具"——
 * 由 5 个可选过滤器组成，全部是 AND 关系。
 *
 * <h3>为什么用 @Data 而不是 record</h3>
 * <p>
 * Spring Boot 的 {@code @ConfigurationProperties} 绑定嵌套 Map 时，
 * record 需要显式 {@code @ConstructorBinding} 且字段名与 yml key 严格对应。
 * 用普通 JavaBean 更稳、更灵活（可以给字段默认值）。
 *
 * <h3>字段与 yml 映射</h3>
 * <pre>
 * YAML                          字段
 * ─────────────────────────     ─────────────────────
 * description: "..."            description
 * include-categories: [...]     includeCategories
 * include-tools: [...]          includeTools
 * include-sources: [...]        includeSources
 * exclude-tools: [...]          excludeTools
 * min-security-level: 4         minSecurityLevel
 * </pre>
 */
@Data
public class ToolProfile {

    /** Profile 描述——仅日志用，不影响过滤逻辑 */
    private String description = "";

    /**
     * 按业务分类包含——对应 {@code ToolDescriptor.category()}。
     * <p>取值示例：calculation / order / todo / memory / entertainment / risk
     */
    private List<String> includeCategories = new ArrayList<>();

    /**
     * 按工具名显式白名单——直接匹配 {@code ToolDescriptor.name()}。
     */
    private List<String> includeTools = new ArrayList<>();

    /**
     * 按来源包含——LOCAL / MCP。
     * <p>不填表示不限制来源。
     */
    private List<ToolSource> includeSources = new ArrayList<>();

    /**
     * 显式黑名单——无论 include 怎样都排除。
     * <p>优先级最高，最后执行。
     */
    private List<String> excludeTools = new ArrayList<>();

    /**
     * 最低密级要求（D69 RBAC 预留）。
     * <p>本轮仅解析，不过滤——D69 会启用。
     * <p>null = 不限制密级。
     */
    private Integer minSecurityLevel;

    /** 是否配置了任何 include 条件——用于判断"空 = 全量" 还是 "空 = 空集" */
    public boolean hasIncludeConditions() {
        return !includeCategories.isEmpty()
                || !includeTools.isEmpty()
                || !includeSources.isEmpty();
    }
}