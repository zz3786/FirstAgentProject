package org.example.toolregistry;

/**
 * 工具刷新监听器（D68）
 *
 * <h3>用途</h3>
 * <p>
 * 工具注册中心的内容发生变化时（
 * {@code ToolRefreshService.refresh()} 执行完成），
 * 所有实现本接口的 Bean 会收到通知。
 *
 * <h3>为什么需要它</h3>
 * <p>
 * 有些消费方会"缓存"工具清单以提升性能——例如
 * {@code ChatService.wrappedCallbacks} 就是一个经过包装的工具数组。
 * 若刷新时无人通知它，缓存就会变成陈旧数据。
 *
 * <p>通过实现本接口，消费方可以在"工具清单变化"时主动失效缓存。
 *
 * <h3>为什么用回调而非发布订阅事件</h3>
 * <p>
 * Spring 的 {@code ApplicationEventPublisher} 也能做类似的事，
 * 但：
 * <ul>
 *   <li>事件发布要求消费方感知"事件类"——引入额外类型依赖</li>
 *   <li>回调方式更轻量——一个方法即可，语义清晰</li>
 *   <li>Spring 会自动把所有实现类注入到 {@code List<ToolRefreshListener>}</li>
 * </ul>
 *
 * <h3>调用时序</h3>
 * <p>
 * 由 {@code ToolRefreshService} 保证：<b>先完成 ToolRegistry 内容更新，
 * 再依次调用所有 listener</b>。因此 listener 在回调中从
 * {@code ToolRegistry} 读到的数据一定是刷新后的最新数据。
 */
@FunctionalInterface
public interface ToolRefreshListener {

    /**
     * 工具清单刷新后的回调。
     * <p>
     * 实现者应当在此方法中：
     * <ul>
     *   <li>清理本地缓存</li>
     *   <li>重新从 ToolRegistry 拉取数据</li>
     *   <li>记录日志</li>
     * </ul>
     *
     * <p><b>注意</b>：本方法在刷新线程同步调用——
     * 实现者应保证快速返回，避免阻塞刷新流程。
     * 若需要耗时的重建操作，考虑异步执行。
     */
    void onToolsRefreshed();
}