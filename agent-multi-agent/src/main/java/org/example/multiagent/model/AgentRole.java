package org.example.multiagent.model;

/**
 * 多 Agent 角色枚举（D71）
 *
 * <h3>设计原则</h3>
 * <p>
 * 角色是"闭集"——由代码定义，不从 yml 动态加载。原因：
 * <ul>
 *   <li>角色决定路由（{@code TaskDispatcher} 按 role 分发）——拼错字符串会导致消息路由失败</li>
 *   <li>角色决定权限（D73 每个角色有独立 ToolProfile）——运行时改名等于绕过 RBAC</li>
 *   <li>枚举带来编译期检查——{@code switch} 覆盖所有分支，新增角色编译器会提醒</li>
 * </ul>
 *
 * <p>
 * 如果未来要支持"用户自定义角色"，应该新建 {@code customRoles} 字段，
 * 而不是把枚举改成 String——保持核心角色的类型安全。
 *
 * <h3>为什么没有 HUMAN 角色</h3>
 * <p>
 * 人工介入走 {@code agent-core} 的 HITL 机制，不参与多 Agent 的
 * 消息总线——它的交互模式是"审批"，不是"回答"，混在一起会污染语义。
 */
public enum AgentRole {

    /**
     * 主管 Agent——接收用户请求，拆解任务，分发给 Worker（D72）
     */
    SUPERVISOR("SUPERVISOR", "主管 Agent", false),

    /**
     * 财务专家 Agent——处理财务、订单、金额类问题（D73）
     */
    FINANCIAL("FINANCIAL", "财务专家", true),

    /**
     * 技术专家 Agent——处理技术选型、代码、架构类问题（D73）
     */
    TECHNICAL("TECHNICAL", "技术专家", true),

    /**
     * 法务专家 Agent——处理合同、合规、风险类问题（D73）
     */
    LEGAL("LEGAL", "法务专家", true),

    /**
     * 仲裁者 Agent——D76 冲突处理时启用，用于打破僵局
     */
    ARBITER("ARBITER", "仲裁者", false);

    /** 角色代码——用于日志、审计、MQ 路由 key 的稳定标识 */
    private final String code;

    /** 展示名——中文，用于前端与日志 */
    private final String displayName;

    /** 是否为 Worker——用于 D72 分发时快速判断 */
    private final boolean worker;

    AgentRole(String code, String displayName, boolean worker) {
        this.code = code;
        this.displayName = displayName;
        this.worker = worker;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isWorker() {
        return worker;
    }

    /**
     * 从 code 反查角色（忽略大小写）
     *
     * @return 对应角色；找不到返回 {@code null}
     */
    public static AgentRole fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (AgentRole r : values()) {
            if (r.code.equalsIgnoreCase(code)) {
                return r;
            }
        }
        return null;
    }

    /**
     * 从 code 反查角色——找不到时返回兜底值。
     * <p>
     * 用于 yml 配置读取——配置错时降级为兜底而不是抛异常，
     * 保证应用能启动（配合启动自检日志暴露问题）。
     */
    public static AgentRole fromCodeOrDefault(String code, AgentRole fallback) {
        AgentRole r = fromCode(code);
        return r == null ? fallback : r;
    }
}