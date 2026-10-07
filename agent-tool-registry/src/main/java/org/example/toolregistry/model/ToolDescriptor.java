package org.example.toolregistry.model;

import org.springframework.ai.tool.ToolCallback;

import java.util.Set;

/**
 * 工具元数据描述符（D67）
 *
 * <h3>为什么需要它</h3>
 * <p>
 * Spring AI 原生只有 {@link ToolCallback}——它携带 {@code name} /
 * {@code description} / {@code inputSchema}，但没有：
 * <ul>
 *   <li>工具来源（本地 / MCP）</li>
 *   <li>业务分类（计算 / 订单 / RAG / 记忆）</li>
 *   <li>安全语义（是否危险、是否只读）</li>
 *   <li>权限要求（D69 RBAC 会用）</li>
 *   <li>注册时间 / 注册者（审计用）</li>
 * </ul>
 * {@code ToolDescriptor} 就是把这层元数据补齐，让 {@code ToolRegistry}
 * 能按维度索引、过滤、校验。
 *
 * <h3>为什么用 record</h3>
 * <p>
 * 工具元数据一旦注册就不应再修改——需要更新时用
 * {@code register()} 覆盖整个 Descriptor。record 的不可变语义
 * 天然防止"半更新"状态。
 *
 * <h3>字段分组</h3>
 * <ul>
 *   <li>基础元数据：name / description</li>
 *   <li>来源维度：source / sourceId</li>
 *   <li>业务分类：category</li>
 *   <li>安全语义：readOnly / destructive / idempotent / openWorld</li>
 *   <li>权限：requiredRoles（D69 用，D67 先留空）</li>
 *   <li>运行时引用：callback</li>
 *   <li>审计：registeredAt / registeredBy</li>
 * </ul>
 *
 * @param name          工具名——全局唯一，作为注册 key
 * @param description   工具描述——供 LLM 判断是否调用
 * @param source        来源：LOCAL / MCP
 * @param sourceId      来源标识：本地填 "local"；MCP 填 Server 连接名（如 "agent-mcp-server"）
 * @param category      业务分类：calculation / order / rag / memory / entertainment / risk
 * @param readOnlyHint  是否只读——查询/计算类填 true
 * @param destructiveHint 是否有破坏性——删除/清空类填 true
 * @param idempotentHint 是否幂等——同参数重复调用等价
 * @param openWorldHint 是否访问外部系统——访问 DB/API/文件填 true
 * @param requiredRoles 允许调用的角色集合——空集表示所有人可调（D69 启用）
 * @param callback      Spring AI 原始 ToolCallback 引用
 * @param registeredAt  注册时间戳
 * @param registeredBy  注册者：bootstrap / runtime-refresh / mcp-sync
 */
public record ToolDescriptor(
        String name,
        String description,

        ToolSource source,
        String sourceId,
        String category,

        boolean readOnlyHint,
        boolean destructiveHint,
        boolean idempotentHint,
        boolean openWorldHint,

        Set<String> requiredRoles,

        ToolCallback callback,

        long registeredAt,
        String registeredBy
) {

    /**
     * 紧凑构造器——做基础校验。
     * <p>
     * 校验不通过直接抛异常，让问题在注册时暴露——
     * 而不是等到 LLM 调用工具时才发现元数据是错的。
     */
    public ToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        if (callback == null) {
            throw new IllegalArgumentException("工具 " + name + " 的 callback 不能为空");
        }
        if (source == null) {
            throw new IllegalArgumentException("工具 " + name + " 的 source 不能为空");
        }
        if (requiredRoles == null) {
            requiredRoles = Set.of();
        }
    }

    /**
     * 判断是否允许指定角色调用。
     * <p>
     * 空 requiredRoles 表示"所有人可调"。
     * D67 阶段所有工具都返回 true——D69 RBAC 会实际使用。
     */
    public boolean isCallableBy(Set<String> userRoles) {
        if (requiredRoles.isEmpty()) {
            return true;
        }
        if (userRoles == null || userRoles.isEmpty()) {
            return false;
        }
        return requiredRoles.stream().anyMatch(userRoles::contains);
    }
}
