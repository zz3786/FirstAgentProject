package org.example.core.toolbootstrap;

import lombok.extern.slf4j.Slf4j;
import org.example.common.utils.TextUtils;
import org.example.core.plan.config.PlanProperties;
import org.example.rag.retrieval.tools.RetrievalPreferenceTools;
import org.example.toolregistry.ToolRegistry;
import org.example.toolregistry.model.ToolDescriptor;
import org.example.toolregistry.model.ToolSource;
import org.example.tools.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具注册启动器（D67 建 + D68 拆分）
 *
 * <h3>D68 的结构调整</h3>
 * <p>
 * 把"启动时执行一次"和"可被复用的注册逻辑"拆开：
 * <ul>
 *   <li>{@link #bootstrap()} —— 启动入口，{@code @EventListener(ApplicationReadyEvent)}
 *       触发，执行一次</li>
 *   <li>{@link #registerAllTools()} —— 纯粹的"扫描 + 注册"逻辑，
 *       被 {@link ToolRefreshService} 在运行时反复调用</li>
 * </ul>
 *
 * <h3>为什么不注入 ToolRefreshListener</h3>
 * <p>
 * D68 的通知机制只在<b>运行时刷新路径</b>使用：
 * <pre>
 *   ToolRefreshService.refresh()
 *     → notifyListeners()
 *     → ChatService.onToolsRefreshed()
 * </pre>
 *
 * <p>启动路径的通知不需要 listener——因为 {@code ChatService} 用
 * {@code @EventListener(ApplicationReadyEvent.class)} + {@code @Order(100)}
 * 保证自己在本类（{@code @Order(50)}）之后执行。
 *
 * <p>这样做避免了两个问题：
 * <ol>
 *   <li>本类不需要感知 listener 的存在——降低耦合</li>
 *   <li>避免 {@code Bootstrap → List<Listener> → ChatService → ToolRegistry}
 *       的间接依赖链，规避循环依赖风险</li>
 * </ol>
 *
 * <h3>异常策略</h3>
 * <p>
 * 单个工具注册失败（如一致性校验不通过）只记日志、跳过该工具，
 * 不阻断其他工具注册，也不阻断应用启动。
 */
@Slf4j
@Component
public class ToolRegistrationBootstrap {

    /** MCP Client 提供的远端工具 Provider——可能不存在 */
    private final ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider;

    /** 工具注册中心 */
    private final ToolRegistry toolRegistry;

    /** Plan 配置——用于白名单一致性检查 */
    private final PlanProperties planProperties;

    // ==================== 本地工具对象（构造注入）====================

    private final CalculatorTools calculatorTools;
    private final TextAnalysisTools textAnalysisTools;
    private final OrderTools orderTools;
    private final TodoTools todoTools;
    private final RiskTools riskTools;
    private final EntertainmentTools entertainmentTools;
    private final PreferenceTools preferenceTools;
    private final LongMemoryTools longMemoryTools;
    private final RetrievalPreferenceTools retrievalPreferenceTools;
    private final InterestTools interestTools;

    public ToolRegistrationBootstrap(
            ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider,
            ToolRegistry toolRegistry,
            PlanProperties planProperties,
            CalculatorTools calculatorTools,
            TextAnalysisTools textAnalysisTools,
            OrderTools orderTools,
            TodoTools todoTools,
            RiskTools riskTools,
            EntertainmentTools entertainmentTools,
            PreferenceTools preferenceTools,
            LongMemoryTools longMemoryTools,
            RetrievalPreferenceTools retrievalPreferenceTools,
            InterestTools interestTools) {
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
        this.toolRegistry = toolRegistry;
        this.planProperties = planProperties;
        this.calculatorTools = calculatorTools;
        this.textAnalysisTools = textAnalysisTools;
        this.orderTools = orderTools;
        this.todoTools = todoTools;
        this.riskTools = riskTools;
        this.entertainmentTools = entertainmentTools;
        this.preferenceTools = preferenceTools;
        this.longMemoryTools = longMemoryTools;
        this.retrievalPreferenceTools = retrievalPreferenceTools;
        this.interestTools = interestTools;
    }

    // ==================== 启动入口 ====================

    /**
     * 启动时入口——只负责时序判断和一次日志。
     * <p>
     * 具体注册逻辑委托给 {@link #registerAllTools()}。
     * <p>
     * {@code @Order(50)} 保证早于 {@code ChatService.onApplicationReady()}
     * （后者 {@code @Order(100)}）执行。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(50)
    public void bootstrap() {
        long start = System.currentTimeMillis();
        log.info("========== [D67] 工具注册启动 ==========");

        // ① 扫描 + 注册
        registerAllTools();

        // ② 汇总打印
        printSummary(System.currentTimeMillis() - start);

        // ③ 白名单一致性检查
        checkWhitelistConsistency();

        // ★ 不做 notifyListeners——启动路径的通知由 ChatService
        //   通过 @EventListener(ApplicationReadyEvent) + @Order(100) 自己完成
    }

    /**
     * 纯粹的注册逻辑——可被启动流程和刷新流程复用。
     *
     * <p>调用方需要保证：调用时 {@link ToolRegistry} 处于可写状态
     * （即：不在刷新中途）。{@code ToolRefreshService} 用单飞锁保证。
     *
     * <p><b>幂等性</b>：本方法会<b>覆盖</b>同名工具——
     * 因此可以直接调用，不需要先 clear。
     * {@code ToolRefreshService} 会先 clear 再调本方法，以保证"下线工具"也能被清掉。
     */
    public void registerAllTools() {
        try {
            // ① 扫描本地 @Tool
            List<ToolDescriptor> localDescriptors = scanLocalTools();
            toolRegistry.registerAll(localDescriptors);

            // ② 从 MCP Client 拉取远端工具
            List<ToolDescriptor> mcpDescriptors = fetchMcpTools();
            toolRegistry.registerAll(mcpDescriptors);

        } catch (Exception e) {
            log.error("[D67/D68] 工具注册过程发生异常", e);
        }
    }

    // ==================== 本地扫描 ====================

    private List<ToolDescriptor> scanLocalTools() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(
                        calculatorTools,
                        textAnalysisTools,
                        orderTools,
                        todoTools,
                        riskTools,
                        entertainmentTools,
                        preferenceTools,
                        longMemoryTools,
                        retrievalPreferenceTools,
                        interestTools
                )
                .build()
                .getToolCallbacks();

        List<ToolDescriptor> descriptors = new ArrayList<>(callbacks.length);
        for (ToolCallback cb : callbacks) {
            try {
                descriptors.add(toLocalDescriptor(cb));
            } catch (Exception e) {
                log.warn("[D67] 本地工具 {} 注册失败，跳过: {}",
                        cb.getToolDefinition().name(), e.getMessage());
            }
        }
        log.info("[D67] 扫描到本地 @Tool 工具 {} 个（成功入参 {} 个）",
                callbacks.length, descriptors.size());
        return descriptors;
    }

    private ToolDescriptor toLocalDescriptor(ToolCallback cb) {
        var def = cb.getToolDefinition();
        String name = def.name();

        return new ToolDescriptor(
                name,
                def.description(),
                ToolSource.LOCAL,
                "local",
                inferCategory(name),
                inferReadOnly(name),
                inferDestructive(name),
                true,                // 本地工具默认幂等
                false,               // 不访问外部系统
                Set.of(),            // D69 会填权限
                cb,
                System.currentTimeMillis(),
                "bootstrap"
        );
    }

    // ==================== MCP 拉取 ====================

    private List<ToolDescriptor> fetchMcpTools() {
        try {
            ToolCallbackProvider provider = mcpToolCallbackProvider.getIfAvailable();
            if (provider == null) {
                log.info("[D67] 无 MCP ToolCallbackProvider——跳过远端工具");
                return List.of();
            }

            ToolCallback[] callbacks = provider.getToolCallbacks();
            if (callbacks == null || callbacks.length == 0) {
                log.warn("[D67] MCP Provider 返回空——检查 MCP Server 是否启动");
                return List.of();
            }

            List<ToolDescriptor> descriptors = new ArrayList<>(callbacks.length);
            for (ToolCallback cb : callbacks) {
                try {
                    descriptors.add(toMcpDescriptor(cb));
                } catch (Exception e) {
                    log.warn("[D67] MCP 工具 {} 注册失败，跳过: {}",
                            cb.getToolDefinition().name(), e.getMessage());
                }
            }
            log.info("[D67] 拉取到 MCP 工具 {} 个（成功入参 {} 个）",
                    callbacks.length, descriptors.size());
            return descriptors;

        } catch (Exception e) {
            log.warn("[D67] 拉取 MCP 工具失败，降级为纯本地: {}", e.getMessage());
            return List.of();
        }
    }

    private ToolDescriptor toMcpDescriptor(ToolCallback cb) {
        var def = cb.getToolDefinition();
        String name = def.name();

        // 一致性校验——名字与 schema 必须语义匹配
        validateMcpConsistency(name, def.inputSchema());

        return new ToolDescriptor(
                name,
                def.description(),
                ToolSource.MCP,
                "agent-mcp-server",
                inferCategory(name),
                inferReadOnly(name),
                inferDestructive(name),
                true,
                true,                // MCP 工具访问外部系统
                Set.of(),
                cb,
                System.currentTimeMillis(),
                "bootstrap"
        );
    }

    /**
     * 工具签名一致性校验
     * <p>
     * 按命名约定检测"名字与 schema 语义不符"——D66 遇到过
     * {@code mcp_calculate} 描述是数学运算但参数是 {@code orderId} 的 bug。
     */
    private void validateMcpConsistency(String name, String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return;
        }
        if (name.contains("calculate")) {
            boolean hasAll = inputSchema.contains("\"a\"")
                    && inputSchema.contains("\"b\"")
                    && inputSchema.contains("\"operation\"");
            if (!hasAll) {
                throw new IllegalStateException(
                        "工具 [" + name + "] 名字含 'calculate' 但 schema 缺少 a/b/operation。");
            }
        }
        if (name.contains("OrderStatus")) {
            if (!inputSchema.contains("orderId")) {
                throw new IllegalStateException(
                        "工具 [" + name + "] 名字含 'OrderStatus' 但 schema 缺少 orderId。");
            }
        }
    }

    // ==================== 分类与语义推断 ====================

    private String inferCategory(String name) {
        if (name == null) return "other";
        String n = name.toLowerCase();
        if (n.contains("calculate"))                         return "calculation";
        if (n.contains("order"))                             return "order";
        if (n.contains("todo"))                              return "todo";
        if (n.contains("memory") || n.contains("memor"))     return "memory";
        if (n.contains("preference"))                        return "preference";
        if (n.contains("interest"))                          return "interest";
        if (n.contains("retrieval"))                         return "retrieval";
        if (n.contains("joke") || n.contains("quote"))       return "entertainment";
        if (n.contains("risky") || n.contains("risk"))       return "risk";
        if (n.contains("analyzetext"))                       return "text";
        return "other";
    }

    private boolean inferReadOnly(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.startsWith("get") || n.startsWith("list")
                || n.contains("calculate") || n.contains("analyze")
                || n.contains("search") || n.contains("query");
    }

    private boolean inferDestructive(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("delete") || n.contains("clear")
                || n.contains("remove") || n.contains("drop");
    }

    // ==================== 汇总与一致性检查 ====================

    private void printSummary(long costMs) {
        var all = toolRegistry.listAll();
        long localCount = toolRegistry.listBySource(ToolSource.LOCAL).size();
        long mcpCount = toolRegistry.listBySource(ToolSource.MCP).size();

        log.info("[D67] 工具注册完成：总计 {} 个（本地 {} / MCP {}），耗时 {}ms",
                all.size(), localCount, mcpCount, costMs);

        log.info("[D67] ---- 本地工具 ({}) ----", localCount);
        toolRegistry.listBySource(ToolSource.LOCAL).forEach(d ->
                log.info("[D67]   [{}] {} - {}",
                        d.category(), d.name(), TextUtils.truncate(d.description(), 50)));

        log.info("[D67] ---- MCP 远端工具 ({}) ----", mcpCount);
        toolRegistry.listBySource(ToolSource.MCP).forEach(d ->
                log.info("[D67]   [{}] {} - {}",
                        d.category(), d.name(), TextUtils.truncate(d.description(), 50)));
    }

    /**
     * 白名单一致性检查
     * <p>
     * 检查 yml 白名单里的工具是否都能在 ToolRegistry 里找到。
     * 缺失的工具在启动时 WARN，让运维提前发现配置错误。
     */
    private void checkWhitelistConsistency() {
        var allowed = planProperties.getAllowedTools();
        if (allowed == null || allowed.isEmpty()) {
            return;
        }

        Set<String> available = toolRegistry.listNames();
        Set<String> missing = new HashSet<>(allowed);
        missing.removeAll(available);

        if (!missing.isEmpty()) {
            log.warn("""
                    [D67] ⚠ 白名单一致性检查失败：
                      yml 白名单里有 {} 个工具在 ToolRegistry 里不存在
                      缺失的工具: {}
                      当前可用工具: {}
                    """, missing.size(), missing, available);
        } else {
            log.info("[D67] ✅ 白名单一致性检查通过：{} 个工具全部可用", allowed.size());
        }
    }

}