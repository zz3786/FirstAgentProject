package org.example.toolregistry;

import org.example.toolregistry.model.ToolDescriptor;
import org.example.toolregistry.model.ToolSource;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 工具注册中心（D67）
 *
 * <h3>职责边界</h3>
 * <p>
 * <b>只做两件事</b>：
 * <ol>
 *   <li>存储 + 索引工具元数据（注册、注销、查询）</li>
 *   <li>按条件导出 {@link ToolCallback} 供下游消费</li>
 * </ol>
 * <p>
 * <b>不做</b>：
 * <ul>
 *   <li>不负责"发现工具"——那是 {@code ToolRegistrationBootstrap} 的职责</li>
 *   <li>不负责"包装工具"（SafeToolCallback）——那是 ChatService 的职责</li>
 *   <li>不负责"鉴权"——D69 会在更外层做</li>
 * </ul>
 *
 * <h3>为什么拆"存储"和"发现"</h3>
 * <p>
 * 存储是稳定的（增删改查），发现方式是易变的（今天从 Spring 容器扫、
 * 明天从 MCP Server 拉、后天从数据库读）。两者分离后，
 * 引入新的工具来源只需写一个新的 Registrar，Registry 完全不动。
 */
public interface ToolRegistry {

    // ==================== 注册 ====================

    /**
     * 注册单个工具。
     * <p>
     * 若同名工具已存在——覆盖并记 WARN 日志。这支持 D68 的"动态刷新"
     * 场景：MCP Server 重启后重新拉取工具，会覆盖旧版本。
     */
    void register(ToolDescriptor descriptor);

    /**
     * 批量注册——一次写锁，比逐个 register 效率高。
     */
    void registerAll(List<ToolDescriptor> descriptors);

    /**
     * 按名字注销。
     * @return true 表示真的删掉了；false 表示本来就不存在
     */
    boolean unregister(String name);

    /**
     * 清空所有注册——D68 动态刷新时先清后注。
     */
    void clear();

    // ==================== 查询 ====================

    /** 按名字取单个工具 */
    Optional<ToolDescriptor> get(String name);

    /** 全量列表 */
    List<ToolDescriptor> listAll();

    /** 按来源过滤：LOCAL / MCP */
    List<ToolDescriptor> listBySource(ToolSource source);

    /** 按来源标识过滤：如 "agent-mcp-server" */
    List<ToolDescriptor> listBySourceId(String sourceId);

    /** 按业务分类过滤：calculation / order / rag ... */
    List<ToolDescriptor> listByCategory(String category);

    /** 所有已注册工具名——PlanValidator 白名单用 */
    Set<String> listNames();

    // ==================== 消费 ====================

    /** 取全部原始 ToolCallback——ChatService 用 */
    ToolCallback[] getCallbacks();

    /**
     * 按白名单取 ToolCallback——只返回名字在集合里的工具。
     * <p>Plan-and-Execute 用这个限制可用工具集。
     */
    ToolCallback[] getCallbacks(Set<String> allowedNames);
}