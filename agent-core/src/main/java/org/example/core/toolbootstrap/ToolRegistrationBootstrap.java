package org.example.core.toolbootstrap;

import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Set;

/**
 * 工具注册启动器（D67）
 *
 * <h3>职责</h3>
 * <p>
 * 应用启动完成后，把两类工具注册到 {@link ToolRegistry}：
 * <ol>
 *   <li><b>本地工具</b>——扫描 {@code @Tool} 注解生成 ToolCallback</li>
 *   <li><b>MCP 工具</b>——从 MCP Client 拉取远端工具</li>
 * </ol>
 *
 * <h3>为什么放在 agent-core 而不是独立模块</h3>
 * <p>
 * 本类需要 {@code new} 出所有具体工具类（{@code CalculatorTools} /
 * {@code OrderTools} / {@code LongMemoryTools} 等），这些类分散在
 * {@code agent-tools} / {@code agent-memory} / {@code agent-rag} 里。
 * 它是"启动时的业务编排"——决定哪些工具要暴露给 Agent，属于
 * {@code agent-core} 的职责范畴。
 *
 * <p>把它独立成模块会导致：多一份 pom、多一次 install、
 * 只有一个消费者却要维护模块边界。等未来真有第二个模块要复用时，
 * Move Class 三秒即可抽出。
 *
 * <h3>为什么用 ApplicationReadyEvent 而不是 @PostConstruct</h3>
 * <p>
 * {@code @PostConstruct} 触发时机太早——MCP Client 可能还没完成
 * initialize 握手，拿到的工具清单可能是空的。
 * {@code ApplicationReadyEvent} 在 Spring 上下文完全就绪后触发，
 * MCP Client 的连接、initialize、tools/list 都已经完成。
 *
 * <h3>执行顺序</h3>
 * <p>
 * 用 {@code @Order(50)} 保证本类早于 {@code ChatService.initToolCallbacks()}
 * （后者 {@code @Order(100)}）执行。执行顺序错了，
 * ChatService 取工具时会拿到空注册表。
 *
 * <h3>异常策略</h3>
 * <p>
 * <b>不抛异常</b>——所有工具都注册失败的极端场景下，Agent 会"无工具可用"，
 * 但对话主链路依然可用。这与"降级可用"的生产理念一致。
 * <p>
 * 单个工具注册失败（如一致性校验不通过）只记日志、跳过该工具，
 * 不阻断其他工具注册，也不阻断应用启动。
 */
@Slf4j
@Component
public class ToolRegistrationBootstrap {

    /** 本地 MCP Client 提供的远端工具 Provider——可能不存在，用 ObjectProvider 包一层 */
    private final ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider;

    /** 工具注册中心 */
    private final ToolRegistry toolRegistry;

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
     * 启动完成后扫描并注册所有工具。
     *
     * <p>{@code @Order(50)} 保证早于 {@code ChatService.initToolCallbacks()}
     * （后者 {@code @Order(100)}）。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(50)
    public void bootstrap() {
        long start = System.currentTimeMillis();
        log.info("========== [D67] 工具注册启动 ==========");

        try {
            // ① 扫描本地 @Tool 注解
            List<ToolDescriptor> localDescriptors = scanLocalTools();
            toolRegistry.registerAll(localDescriptors);

            // ② 从 MCP Client 拉取远端工具
            List<ToolDescriptor> mcpDescriptors = fetchMcpTools();
            toolRegistry.registerAll(mcpDescriptors);

            // ③ 汇总打印
            printSummary(System.currentTimeMillis() - start);

        } catch (Exception e) {
            // 兜底——任何未预期的异常都不应阻断应用启动
            log.error("[D67] 工具注册过程发生未预期异常，部分工具可能不可用", e);
        }
    }

    // ==================== ① 扫描本地 @Tool ====================

    /**
     * 扫描本地 {@code @Tool} 注解，包装成 {@link ToolDescriptor}。
     */
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
                // 单个工具构造失败不影响其他——D67 的一致性校验可能误伤
                log.warn("[D67] 本地工具 {} 注册失败，跳过: {}",
                        cb.getToolDefinition().name(), e.getMessage());
            }
        }
        log.info("[D67] 扫描到本地 @Tool 工具 {} 个（成功入参 {} 个）",
                callbacks.length, descriptors.size());
        return descriptors;
    }

    /**
     * 把本地 ToolCallback 转成 ToolDescriptor。
     * <p>
     * 分类 / 语义标注靠"名字 → 属性"规则推断——生产环境可从
     * 自定义注解（如 {@code @ToolMeta(category="order", readOnly=true)}）
     * 或配置表读取。D67 先用最小可行版本。
     */
    private ToolDescriptor toLocalDescriptor(ToolCallback cb) {
        var def = cb.getToolDefinition();
        String name = def.name();

        return new ToolDescriptor(
                name,
                def.description(),
                ToolSource.LOCAL,
                "local",                      // 本地工具的 sourceId 固定
                inferCategory(name),
                inferReadOnly(name),
                inferDestructive(name),
                true,                         // 本地工具默认幂等——纯函数
                false,                        // 不访问外部系统
                Set.of(),                     // D67 不做权限，D69 再填
                cb,
                System.currentTimeMillis(),
                "bootstrap"
        );
    }

    // ==================== ② 拉取 MCP 远端工具 ====================

    /**
     * 从 MCP Client 拉取远端工具。
     * <p>
     * 任一环节失败返回空列表——MCP Server 不可用不应阻断启动。
     */
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
                    // 一致性校验失败的工具跳过——记录 WARN 但不阻断
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

        // ★ D66 遗留 bug 的检测点：名字与 schema 的一致性校验
        validateMcpConsistency(name, def.inputSchema());

        return new ToolDescriptor(
                name,
                def.description(),
                ToolSource.MCP,
                "agent-mcp-server",           // 从 MCP Client 配置可拿到，D67 先写死
                inferCategory(name),
                inferReadOnly(name),
                inferDestructive(name),
                true,                         // 幂等假设——具体看工具实现
                true,                         // MCP 工具访问外部系统
                Set.of(),
                cb,
                System.currentTimeMillis(),
                "bootstrap"
        );
    }

    /**
     * 工具签名一致性校验（D67 新增）
     *
     * <h3>背景</h3>
     * <p>
     * D66 遇到过一个具体 bug：MCP Server 暴露的 {@code mcp_calculate}
     * 描述是"数学运算"，但参数只有 {@code orderId}——描述和 schema
     * 完全对不上。模型看到这个工具会无所适从，调用时也会传错参数。
     *
     * <h3>D67 的策略</h3>
     * <p>
     * 按命名约定做最小可行的校验：名字里含关键词的工具，
     * schema 必须包含对应参数。不通过则抛异常，由调用方捕获后跳过。
     *
     * <h3>为什么抛异常而不是返回 boolean</h3>
     * <p>
     * 抛异常让调用方必须显式处理——不会因为"忘了检查返回值"而
     * 让不合规的工具被静默注册。
     *
     * @throws IllegalStateException schema 与工具名语义不符时抛出
     */
    private void validateMcpConsistency(String name, String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return;
        }

        // 规则 1：名字含 "calculate" → 参数必须有 a / b / operation
        if (name.contains("calculate")) {
            boolean hasAll = inputSchema.contains("\"a\"")
                    && inputSchema.contains("\"b\"")
                    && inputSchema.contains("\"operation\"");
            if (!hasAll) {
                throw new IllegalStateException(
                        "工具 [" + name + "] 名字含 'calculate' 但 schema 缺少 a/b/operation。" +
                                "请检查 agent-mcp-server 端的 @McpTool 注解是否与方法签名匹配。" +
                                " schema=" + inputSchema);
            }
        }

        // 规则 2：名字含 "OrderStatus" → 参数必须有 orderId
        if (name.contains("OrderStatus")) {
            if (!inputSchema.contains("orderId")) {
                throw new IllegalStateException(
                        "工具 [" + name + "] 名字含 'OrderStatus' 但 schema 缺少 orderId。" +
                                " schema=" + inputSchema);
            }
        }

        // 未来可扩展更多规则——或改为从配置表加载
    }

    // ==================== 分类与语义推断 ====================

    /**
     * 从工具名推断业务分类。
     * <p>
     * 生产级做法：定义一个 {@code @ToolMeta} 注解，在工具类上声明分类，
     * 通过反射读取。这里先用名字匹配——简单、无侵入、覆盖当前场景。
     */
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

    /**
     * 推断是否只读。
     * <p>
     * "只读"= 调用不修改任何状态。查询 / 计算 / 分析类属于只读。
     */
    private boolean inferReadOnly(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.startsWith("get") || n.startsWith("list")
                || n.contains("calculate") || n.contains("analyze")
                || n.contains("search") || n.contains("query");
    }

    /**
     * 推断是否有破坏性。
     * <p>
     * "破坏性"= 调用可能导致数据不可逆变化。删除 / 清空类属于破坏性。
     */
    private boolean inferDestructive(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("delete") || n.contains("clear")
                || n.contains("remove") || n.contains("drop");
    }

    // ==================== 汇总打印 ====================

    /**
     * 按来源分组打印注册结果。
     * <p>
     * 分组打印的目的：让启动日志一眼能看出"本地几个、MCP 几个"，
     * 出问题时快速定位是本地工具没注册上还是 MCP 连接失败。
     */
    private void printSummary(long costMs) {
        var all = toolRegistry.listAll();
        long localCount = toolRegistry.listBySource(ToolSource.LOCAL).size();
        long mcpCount = toolRegistry.listBySource(ToolSource.MCP).size();

        log.info("[D67] 工具注册完成：总计 {} 个（本地 {} / MCP {}），耗时 {}ms",
                all.size(), localCount, mcpCount, costMs);

        log.info("[D67] ---- 本地工具 ({}) ----", localCount);
        toolRegistry.listBySource(ToolSource.LOCAL).forEach(d ->
                log.info("[D67]   [{}] {} - {}",
                        d.category(), d.name(), truncate(d.description(), 50)));

        log.info("[D67] ---- MCP 远端工具 ({}) ----", mcpCount);
        toolRegistry.listBySource(ToolSource.MCP).forEach(d ->
                log.info("[D67]   [{}] {} - {}",
                        d.category(), d.name(), truncate(d.description(), 50)));
    }

    private String truncate(String s, int max) {
        if (s == null || s.isBlank()) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}