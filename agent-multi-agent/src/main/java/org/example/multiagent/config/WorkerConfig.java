package org.example.multiagent.config;

import lombok.Data;
import org.example.multiagent.model.AgentRole;

/**
 * 单个 Worker 的配置（D73）
 *
 * <h3>yml 映射</h3>
 * <pre>
 * app:
 *   workers:
 *     financial:
 *       role: FINANCIAL
 *       display-name: 财务专家
 *       tool-profile: worker-financial
 *       system-prompt: |
 *         你是一位资深的财务专家...
 * </pre>
 *
 * <h3>为什么 role 用 AgentRole 枚举而不是 String</h3>
 * <p>
 * Spring Boot 的 {@code @ConfigurationProperties} 支持枚举绑定，
 * 会自动把 yml 里的字符串转成枚举（不区分大小写）。
 * <ul>
 *   <li>yml 写错角色名 → 启动时立刻报错，不会静默失败</li>
 *   <li>代码里直接 switch 枚举——不用再 fromCode 转换</li>
 * </ul>
 *
 * <h3>为什么不用 record</h3>
 * <p>
 * {@code @ConfigurationProperties} 绑定到 record 需要 {@code @ConstructorBinding}，
 * 且字段顺序严格——Bean 更稳（和 {@code ToolProfile} / {@code MultiAgentProperties} 风格一致）。
 */
@Data
public class WorkerConfig {

    /** Worker 负责的角色——决定 WorkerAgentRegistry 的索引 key */
    private AgentRole role;

    /** 展示名——用于日志、审计，不影响逻辑 */
    private String displayName;

    /** ToolProfile 名——在 app-tool-profiles.yml 里定义 */
    private String toolProfile;

    /** System Prompt——定义 Worker 的领域身份 */
    private String systemPrompt;

    /**
     * 校验必填字段
     * <p>
     * Spring Boot 绑定完 yml 后会调用（如果标注了 {@code @PostConstruct}），
     * 但这里只作为工具方法由外部调用——因为 WorkerConfig 是普通 Bean，
     * 由 WorkerProperties 持有，不方便单独加生命周期回调。
     */
    public boolean isValid() {
        return role != null
                && toolProfile != null && !toolProfile.isBlank()
                && systemPrompt != null && !systemPrompt.isBlank();
    }
}