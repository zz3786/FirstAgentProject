package org.example.api.startup;

import lombok.extern.slf4j.Slf4j;
import org.example.core.rbac.config.RbacProperties;
import org.example.core.toolprofile.ToolProfileResolver;
import org.example.core.toolprofile.config.ToolProfileProperties;
import org.example.toolregistry.ToolRegistry;
import org.example.toolregistry.model.ToolDescriptor;
import org.example.toolregistry.model.ToolSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具链启动自检
 *
 * <h3>为什么需要它</h3>
 * <p>
 * D64~D69 建了一套复杂的工具治理体系：
 * <ul>
 *   <li>ToolRegistry——工具注册中心</li>
 *   <li>ToolProfile——业务画像过滤</li>
 *   <li>RBAC——工具级 + 参数级权限</li>
 *   <li>MCP Client——远端工具接入</li>
 * </ul>
 * <p>
 * 每次启动，运维/开发需要"一眼确认"这些环节都正常工作——
 * 而不是翻十几页启动日志逐行 grep。
 *
 * <h3>它做什么</h3>
 * <p>
 * 在 {@code ApplicationReadyEvent} 之后打印一份结构化的状态报告：
 * <ol>
 *   <li>工具注册总览（本地 / MCP 分类）</li>
 *   <li>每个 Profile 的过滤结果</li>
 *   <li>RBAC 配置覆盖率（多少个工具被配置了密级）</li>
 *   <li>关键工具是否可用（calculate / getOrderStatus 等）</li>
 * </ol>
 *
 * <h3>它不做什么</h3>
 * <ul>
 *   <li>不做断言——不因为"某个工具缺失"就抛异常中断启动</li>
 *   <li>不做自动化测试——那是 {@code @SpringBootTest} 的职责</li>
 *   <li>不阻塞启动——打印即完成</li>
 * </ul>
 *
 * <h3>执行时机</h3>
 * <p>
 * 用 {@code @Order(200)}——晚于：
 * <ul>
 *   <li>{@code ToolRegistrationBootstrap.bootstrap()}（{@code @Order(50)}）</li>
 *   <li>{@code ChatService.onApplicationReady()}（{@code @Order(100)}）</li>
 * </ul>
 * 这样自检时所有工具都已注册完毕。
 */
@Slf4j
@Component
public class ToolChainSelfCheck {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ToolProfileResolver toolProfileResolver;

    @Autowired
    private ToolProfileProperties toolProfileProperties;

    @Autowired
    private RbacProperties rbacProperties;

    @EventListener(ApplicationReadyEvent.class)
    @Order(200)
    public void selfCheck() {
        StringBuilder sb = new StringBuilder("\n");

        printHeader(sb);
        printToolOverview(sb);
        printToolProfiles(sb);
        printRbacConfig(sb);
        printKeyTools(sb);
        printFooter(sb);

        // 用 log.info 输出——保证出现在启动日志里
        log.info(sb.toString());
    }

    // ==================== 各段打印 ====================

    private void printHeader(StringBuilder sb) {
        sb.append("╔══════════════════════════════════════════════════════════╗\n");
        sb.append("║          工具链启动自检报告（D70）                        ║\n");
        sb.append("╚══════════════════════════════════════════════════════════╝\n");
    }

    private void printToolOverview(StringBuilder sb) {
        var all = toolRegistry.listAll();
        long localCount = toolRegistry.listBySource(ToolSource.LOCAL).size();
        long mcpCount = toolRegistry.listBySource(ToolSource.MCP).size();

        sb.append("\n【1】工具注册总览\n");
        sb.append("  ├─ 总计     : ").append(all.size()).append(" 个\n");
        sb.append("  ├─ 本地工具 : ").append(localCount).append(" 个\n");
        sb.append("  └─ MCP 工具 : ").append(mcpCount).append(" 个");
        if (mcpCount == 0) {
            sb.append(" ⚠ 检查 MCP Server 是否启动（默认 8086）");
        }
        sb.append("\n");

        // 按 category 汇总
        Map<String, Long> byCategory = all.stream()
                .collect(Collectors.groupingBy(
                        ToolDescriptor::category,
                        TreeMap::new,
                        Collectors.counting()));
        sb.append("  分类分布: ");
        byCategory.forEach((cat, cnt) ->
                sb.append("[").append(cat).append(":").append(cnt).append("] "));
        sb.append("\n");
    }

    private void printToolProfiles(StringBuilder sb) {
        sb.append("\n【2】工具画像过滤结果\n");
        var profiles = toolProfileProperties.getProfiles();

        if (profiles.isEmpty()) {
            sb.append("  ⚠ 未配置任何 profile\n");
            return;
        }

        profiles.keySet().forEach(profileName -> {
            ToolCallback[] callbacks = toolProfileResolver.resolve(profileName);
            String toolNames = Arrays.stream(callbacks)
                    .map(cb -> cb.getToolDefinition().name())
                    .limit(3)
                    .collect(Collectors.joining(", "));
            String more = callbacks.length > 3 ? ", ..." : "";

            sb.append("  ├─ ").append(padRight(profileName, 20))
                    .append(" → ").append(callbacks.length).append(" 个工具")
                    .append(" (").append(toolNames).append(more).append(")\n");
        });

        // 消费方映射
        sb.append("  消费方映射:\n");
        toolProfileProperties.getConsumers().forEach((consumer, profile) ->
                sb.append("  │  ").append(padRight(consumer, 20))
                        .append(" → ").append(profile).append("\n"));
    }

    private void printRbacConfig(StringBuilder sb) {
        sb.append("\n【3】RBAC 权限配置\n");
        sb.append("  ├─ 总开关      : ").append(rbacProperties.isEnabled() ? "✅ 启用" : "⛔ 关闭").append("\n");
        sb.append("  ├─ 参数级校验  : ").append(rbacProperties.getParamValidation().isEnabled() ? "✅ 启用" : "⛔ 关闭").append("\n");
        sb.append("  └─ 已配置密级  : ").append(rbacProperties.getToolRequirements().size()).append(" 个工具\n");

        // 按密级分组
        Map<Integer, List<String>> byLevel = new TreeMap<>();
        rbacProperties.getToolRequirements().forEach((tool, req) -> {
            int level = req.getMinSecurityLevel() == null ? 1 : req.getMinSecurityLevel();
            byLevel.computeIfAbsent(level, k -> new ArrayList<>()).add(tool);
        });

        byLevel.forEach((level, tools) -> {
            sb.append("      密级 ").append(level).append(": ");
            sb.append(tools.stream().limit(5).collect(Collectors.joining(", ")));
            if (tools.size() > 5) {
                sb.append(" ... (共 ").append(tools.size()).append(" 个)");
            }
            sb.append("\n");
        });
    }

    private void printKeyTools(StringBuilder sb) {
        sb.append("\n【4】关键工具可用性\n");

        // 本地工具（必检）
        String[] localKeys = {
                "calculate", "getOrderStatus", "createTodo", "riskyOperation",
                "analyzeText", "getJoke"
        };
        // MCP 工具（可选）
        String[] mcpKeys = {
                "mcp_calculate", "mcp_getOrderStatus"
        };

        Set<String> registered = toolRegistry.listNames();

        sb.append("  本地工具:\n");
        for (String name : localKeys) {
            sb.append("  ├─ ").append(padRight(name, 20))
                    .append(registered.contains(name) ? "✅" : "❌")
                    .append("\n");
        }

        sb.append("  MCP 工具:\n");
        for (String name : mcpKeys) {
            boolean has = registered.contains(name);
            sb.append("  ├─ ").append(padRight(name, 20))
                    .append(has ? "✅" : "⚠ 未注册（MCP Server 未启动？）")
                    .append("\n");
        }
    }

    private void printFooter(StringBuilder sb) {
        sb.append("\n╔══════════════════════════════════════════════════════════╗\n");
        sb.append("║  自检完成                                                 ║\n");
        sb.append("║  若全部 ✅ → 工具链正常                                   ║\n");
        sb.append("║  有 ⚠/❌ → 检查上方提示                                   ║\n");
        sb.append("╚══════════════════════════════════════════════════════════╝\n");
    }

    /** 简易右填充——中文按 1 字符算 */
    private String padRight(String s, int len) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) {
            sb.append(' ');
        }
        return sb.toString();
    }
}