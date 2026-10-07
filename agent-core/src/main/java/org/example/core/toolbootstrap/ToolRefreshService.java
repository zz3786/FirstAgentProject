package org.example.core.toolbootstrap;

import lombok.extern.slf4j.Slf4j;
import org.example.toolregistry.ToolRefreshListener;
import org.example.toolregistry.ToolRegistry;
import org.example.toolregistry.model.ToolSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具刷新服务（D68）
 *
 * <h3>职责</h3>
 * <p>在运行时"重新发现"所有工具并更新 {@link ToolRegistry}：
 * <ol>
 *   <li>清空 ToolRegistry 旧内容</li>
 *   <li>重新扫描本地 {@code @Tool} 注解</li>
 *   <li>重新从 MCP Client 拉取远端工具</li>
 *   <li>通知所有 {@link ToolRefreshListener} 刷新缓存</li>
 * </ol>
 *
 * <h3>并发控制</h3>
 * <p>用 {@link AtomicBoolean} 做"单飞锁"——同一时刻只允许一个刷新流程执行。
 * 后到的刷新请求直接返回"已在执行中"，不排队。
 */
@Slf4j
@Service
public class ToolRefreshService {

    private final ToolRegistry toolRegistry;
    private final ToolRegistrationBootstrap bootstrap;
    private final List<ToolRefreshListener> listeners;

    /** 单飞锁：保证同时只有一个刷新流程在跑 */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public ToolRefreshService(ToolRegistry toolRegistry,
                              ToolRegistrationBootstrap bootstrap,
                              List<ToolRefreshListener> listeners) {
        this.toolRegistry = toolRegistry;
        this.bootstrap = bootstrap;
        this.listeners = listeners;
        log.info("[D68] ToolRefreshService 初始化，监听器 {} 个: {}",
                listeners.size(),
                listeners.stream().map(l -> l.getClass().getSimpleName()).toList());
    }

    /**
     * 触发一次工具刷新。
     *
     * @return 刷新结果；若已有刷新在进行中，返回 {@link ToolRefreshResult#skip()}
     */
    public ToolRefreshResult refresh() {
        // ① 单飞锁——已有刷新在执行则立即返回
        if (!refreshing.compareAndSet(false, true)) {
            log.info("[D68] 刷新请求被跳过——已有刷新在执行中");
            return ToolRefreshResult.skip();
        }

        long start = System.currentTimeMillis();
        try {
            log.info("========== [D68] 工具刷新开始 ==========");

            // ② 记录旧状态
            int beforeCount = toolRegistry.listAll().size();

            // ③ 清空
            toolRegistry.clear();

            // ④ 重新发现 + 注册（复用 Bootstrap 的扫描逻辑）
            bootstrap.registerAllTools();

            // ⑤ 计算变化
            int afterCount = toolRegistry.listAll().size();
            int localCount = toolRegistry.listBySource(ToolSource.LOCAL).size();
            int mcpCount = toolRegistry.listBySource(ToolSource.MCP).size();

            // ⑥ 通知所有监听器
            notifyListeners();

            long cost = System.currentTimeMillis() - start;
            log.info("[D68] 工具刷新完成：{} → {} 个（本地 {} / MCP {}），耗时 {}ms",
                    beforeCount, afterCount, localCount, mcpCount, cost);

            return ToolRefreshResult.ok(beforeCount, afterCount, localCount, mcpCount, cost);

        } catch (Exception e) {
            log.error("[D68] 工具刷新失败", e);
            return ToolRefreshResult.fail(e.getMessage());
        } finally {
            refreshing.set(false);
        }
    }

    /**
     * 通知所有监听器。
     * <p><b>隔离原则</b>：某个 listener 抛异常不应影响其他 listener——
     * 每个单独 try-catch，失败只记日志不中断循环。
     */
    private void notifyListeners() {
        if (listeners.isEmpty()) {
            return;
        }
        log.info("[D68] 通知 {} 个 ToolRefreshListener", listeners.size());

        for (ToolRefreshListener listener : listeners) {
            String name = listener.getClass().getSimpleName();
            try {
                listener.onToolsRefreshed();
                log.info("[D68] listener {} 回调成功", name);
            } catch (Exception e) {
                // 单个 listener 失败不影响其他
                log.error("[D68] listener {} 回调失败", name, e);
            }
        }
    }
}