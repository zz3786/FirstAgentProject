package org.example.api.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.example.core.toolbootstrap.ToolRefreshResult;
import org.example.core.toolbootstrap.ToolRefreshService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 工具清单定时刷新（D68）
 *
 * <h3>为什么放在 agent-api 而不是 agent-core</h3>
 * <p>
 * {@link ToolRefreshService}（编排逻辑）在 {@code agent-core}，
 * 但本类（定时触发）放在 {@code agent-api}——因为它们职责不同：
 * <ul>
 *   <li>{@code agent-core} 是<b>库</b>——被多个可部署模块依赖，
 *       不应该自带"启动定时任务"的副作用</li>
 *   <li>{@code agent-api} 是<b>可部署应用</b>——触发入口
 *       （HTTP 接口 / 定时任务）天然属于应用层</li>
 * </ul>
 * <p>
 * 如果放在 {@code agent-core}，那么 {@code agent-mcp-server}
 * 依赖 core 时也会被强制激活这个定时任务——而它本身是工具提供方，
 * 不需要"刷新自己的工具清单"。
 *
 * <h3>为什么需要定时刷新</h3>
 * <p>
 * 长时间运行的 Agent 实例里，MCP Server 的可用性可能变化：
 * <ul>
 *   <li>Server 重启导致工具短暂不可用</li>
 *   <li>Server 上线新工具，但 Agent 侧不知道</li>
 *   <li>Server 下线某些工具，但 Agent 侧还留着旧引用</li>
 * </ul>
 * 定时刷新保证 Agent 侧的工具清单在"可接受延迟"内跟上实际状态。
 *
 * <h3>刷新频率</h3>
 * <p>
 * 通过 {@code app.mcp.tools.refresh-interval-ms} 配置，默认 5 分钟。
 * 每次刷新都要重新调 MCP Server 的 {@code tools/list}——
 * 有网络开销，不宜过频。
 *
 * <h3>调度方式</h3>
 * <p>
 * 用 {@code fixedDelay}（上一次执行完成后间隔 X 毫秒再执行），
 * 不用 {@code fixedRate}——因为刷新耗时可能较长（要网络往返），
 * fixedRate 会导致任务堆积。
 *
 * <h3>开关</h3>
 * <p>
 * 通过 yml 配置 {@code app.mcp.tools.auto-refresh-enabled=false} 可关闭。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "app.mcp.tools.auto-refresh-enabled",
        havingValue = "true",
        matchIfMissing = true     // 默认启用
)
public class McpToolsRefreshScheduler {

    private final ToolRefreshService toolRefreshService;

    public McpToolsRefreshScheduler(ToolRefreshService toolRefreshService) {
        this.toolRefreshService = toolRefreshService;
    }

    /**
     * 定时刷新——默认 5 分钟一次。
     * <p>
     * {@code initialDelayString} 让首次执行延迟到应用完全就绪之后，
     * 避免和启动时的首次注册撞车。
     */
    @Scheduled(
            initialDelayString = "${app.mcp.tools.refresh-interval-ms:300000}",
            fixedDelayString = "${app.mcp.tools.refresh-interval-ms:300000}"
    )
    public void autoRefresh() {
        log.debug("[D68] 定时刷新工具清单触发");
        ToolRefreshResult result = toolRefreshService.refresh();

        if (result.success()) {
            log.info("[D68] 定时刷新成功：{} 个工具（本地 {} / MCP {}），耗时 {}ms",
                    result.afterCount(), result.localCount(),
                    result.mcpCount(), result.costMs());
        } else if (result.skipped()) {
            log.info("[D68] 定时刷新被跳过——已有刷新在执行中");
        } else {
            log.warn("[D68] 定时刷新失败：{}", result.error());
        }
    }
}