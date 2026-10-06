package org.example.mcpserver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP Server 独立启动类（D65）
 *
 * <h3>这个进程做什么</h3>
 * <ol>
 *   <li>启动 Spring Boot Web 容器（端口 8086）</li>
 *   <li>MCP Server Starter 自动扫描 {@code @McpTool} / {@code @McpResource} / {@code @McpPrompt} 注解</li>
 *   <li>把注解方法注册为 MCP 能力（Tools / Resources / Prompts）</li>
 *   <li>在 {@code /mcp} 端点上监听 JSON-RPC 请求</li>
 *   <li>Client 连接时完成 initialize 握手，并响应 {@code tools/list} / {@code resources/list} / {@code prompts/list}</li>
 * </ol>
 *
 * <h3>和 agent-api 的关系</h3>
 * <p>
 * <b>没有编译期依赖，只有运行时的 HTTP 连接。</b>
 * agent-api 是 Host（MCP Client），本模块是 MCP Server。
 * 两者可以独立启动、独立部署、独立扩缩容。
 *
 * <h3>为什么没有 @MapperScan / @EnableRetry</h3>
 * MCP Server 本身无状态，不碰数据库、不碰 Redis、不碰重试。
 * 业务逻辑（如订单查询）在 agent-tools 里，本模块只做“能力暴露”。
 */
@Slf4j
@SpringBootApplication(scanBasePackages = {
        "org.example.mcpserver",
        "org.example.tools"
})
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);

        log.info("""
                
                ══════════════════════════════════════════════
                D65 MCP Server 已启动
                ──────────────────────────────────────────────
                端口      : 8086
                传输协议  : STREAMABLE (HTTP POST + SSE)
                MCP 端点  : http://localhost:8086/mcp
                ──────────────────────────────────────────────
                验证方式  : curl -X POST http://localhost:8081/mcp \\
                             -H "Content-Type: application/json" \\
                             -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
                ══════════════════════════════════════════════
                """);
    }
}