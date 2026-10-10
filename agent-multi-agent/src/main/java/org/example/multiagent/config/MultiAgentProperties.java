package org.example.multiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 多智能体协作配置（D71）
 *
 * <h3>yml 前缀</h3>
 * <pre>
 * app:
 *   multi-agent:
 *     enabled: true
 *     supervisor: {...}
 *     reflection: {...}
 *     conflict: {...}
 *     aggregation: {...}
 * </pre>
 *
 * <h3>为什么不用 @RefreshScope</h3>
 * <p>
 * 大部分字段是"启动时决定"的（如 maxSubtasks）。
 * 只有 {@code reflection.qualityThreshold} 可能有"运行时调优"的需求——
 * 但那个场景低频，重启即可。加 @RefreshScope 会让本类每次访问都要走代理，
 * 得不偿失。未来真需要热刷新时再加。
 *
 * <h3>为什么不用 record</h3>
 * <p>
 * {@code @ConfigurationProperties} 对嵌套 record 的绑定需要
 * {@code @ConstructorBinding} 且字段顺序敏感——Bean 更稳。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.multi-agent")
public class MultiAgentProperties {

    /** 总开关——关闭后 MultiAgentFacade 直接返回失败响应 */
    private boolean enabled = true;

    /** 主管 Agent 配置（D72 使用） */
    private Supervisor supervisor = new Supervisor();

    /** 反思机制配置（D71 使用） */
    private Reflection reflection = new Reflection();

    /** 冲突处理配置（D76 使用） */
    private Conflict conflict = new Conflict();

    /** 结果聚合配置（D75 使用） */
    private Aggregation aggregation = new Aggregation();

    // ==================== 嵌套配置 ====================

    /**
     * 主管 Agent 配置
     */
    @Data
    public static class Supervisor {
        /** 拆解出的最大子任务数——防止模型拆出 20 个子任务 */
        private int maxSubtasks = 6;

        /** 单个子任务的最大执行时长（毫秒） */
        private long perSubtaskTimeoutMs = 60_000L;

        /** 整体最大执行时长（毫秒）——超过则中止所有未完成子任务 */
        private long totalTimeoutMs = 180_000L;
    }

    /**
     * 反思配置
     */
    @Data
    public static class Reflection {
        /** 是否启用反思——false 时 SelfReflectionAdvisor 变成透传 */
        private boolean enabled = true;

        /**
         * 反思最大轮次
         * <p>
         * <b>含义</b>：最多评估 N 次。第 1 次评估后如果 rejected，
         * 会用改进答案作为"当前答案"，进入第 2 次评估。
         * <p>
         * <b>为什么默认 2 而不是更大</b>：
         * 反思是"贵"操作（每次评估 = 1 次 LLM 调用）。
         * 实践中 2 轮已能覆盖大多数场景，更多轮次收益递减。
         */
        private int maxRounds = 2;

        /**
         * 质量阈值——分数 ≥ 此值则接受
         * <p>
         * <b>为什么不用 LLM 的 accepted 字段</b>：
         * LLM 的 accepted 判断有时过严（"完美主义"），
         * 用分数兜底——即使 LLM 说 rejected，只要分数够高也接受。
         */
        private double qualityThreshold = 0.7;
    }

    /**
     * 冲突处理配置（D76）
     */
    @Data
    public static class Conflict {
        /** 策略：MAJORITY_VOTE / ARBITER */
        private String strategy = "MAJORITY_VOTE";

        /** 仲裁者角色（strategy=ARBITER 时使用） */
        private String arbiterRole = "SUPERVISOR";
    }

    /**
     * 结果聚合配置（D75）
     */
    @Data
    public static class Aggregation {
        /** 策略：CONCAT / LLM_SYNTHESIS / WEIGHTED */
        private String strategy = "LLM_SYNTHESIS";

        /** 每条 Worker 结果注入聚合 Prompt 的最大字符数 */
        private int maxResultChars = 2000;
    }
}