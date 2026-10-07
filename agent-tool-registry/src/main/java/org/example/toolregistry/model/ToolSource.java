package org.example.toolregistry.model;

/**
 * 工具来源枚举（D67）
 *
 * <p>用于区分工具的物理位置——决定调用时走本地反射还是 HTTP JSON-RPC。
 * <p>该维度是 ToolRegistry 的一级索引，启动日志按它分组打印。
 */
public enum ToolSource {

    /**
     * 本地工具——通过 {@code @Tool} 注解暴露。
     * <p>和 Agent 在同一个 JVM，通过反射直接调用。
     */
    LOCAL,

    /**
     * MCP 远程工具——通过 {@code @McpTool} 注解暴露。
     * <p>部署在独立进程，通过 HTTP + JSON-RPC 调用。
     */
    MCP
}
