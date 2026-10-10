package org.example.multiagent.bus;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.model.AgentMessage;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息关联注册表（D74）
 *
 * <h3>它解决什么问题</h3>
 * <p>
 * 同步语义的 {@code sendAndWait} 需要"发消息 → 阻塞等待 → 拿到响应"。
 * 但 MQ 是异步的——消息响应由另一个线程（listener 线程）接收。
 * <p>
 * 本类充当"线程间的桥"：
 * <pre>
 *   业务线程                       Listener 线程
 *      │                              │
 *   ① register(correlationId)         │
 *      → 得到 Future                   │
 *      │                              │
 *   ② 发 MQ 消息                      │
 *      │                              │
 *   ③ future.get(timeout) 阻塞        │
 *      │                              │
 *      │                       ④ 收到 RESULT
 *      │                          complete(correlationId, msg)
 *      │                              │
 *   ⑤ 拿到结果 ◄───────────────────┘
 * </pre>
 *
 * <h3>为什么用 ConcurrentHashMap</h3>
 * <p>
 * <ul>
 *   <li>多业务线程并发 register——写</li>
 *   <li>多 listener 线程并发 complete——写</li>
 *   <li>无锁读——不需要</li>
 * </ul>
 * 天然适合 ConcurrentHashMap。
 *
 * <h3>内存泄漏防护</h3>
 * <p>
 * 三种清理路径：
 * <ol>
 *   <li>正常：complete 时 remove</li>
 *   <li>超时：sendAndWait 的 catch 里 cancel</li>
 *   <li>兜底：finally 里再次 cancel（双保险）</li>
 * </ol>
 * 三层保证——挂起的 Future 不会无限累积。
 */
@Slf4j
@Component
public class MessageCorrelationRegistry {

    /** correlationId → 等待响应的 Future */
    private final ConcurrentHashMap<String, CompletableFuture<AgentMessage>> pending
            = new ConcurrentHashMap<>();

    /**
     * 注册一个新的等待——返回 Future 给业务线程
     *
     * @param correlationId 关联 ID
     * @return Future——业务线程 get() 会阻塞直到响应或超时
     */
    public CompletableFuture<AgentMessage> register(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId 不能为空");
        }
        CompletableFuture<AgentMessage> future = new CompletableFuture<>();
        CompletableFuture<AgentMessage> existing = pending.putIfAbsent(correlationId, future);
        if (existing != null) {
            // 理论不会发生——correlationId 唯一。防御性检查。
            throw new IllegalStateException(
                    "correlationId 冲突: " + correlationId);
        }
        if (log.isDebugEnabled()) {
            log.debug("[Correlation] 注册: correlationId={}, pendingCount={}",
                    correlationId, pending.size());
        }
        return future;
    }

    /**
     * 完成一个等待——listener 线程收到响应时调用
     *
     * @param correlationId 关联 ID
     * @param message       响应消息
     * @return true = 有对应的等待被完成；false = 无（可能已超时）
     */
    public boolean complete(String correlationId, AgentMessage message) {
        if (correlationId == null) {
            return false;
        }
        CompletableFuture<AgentMessage> future = pending.remove(correlationId);
        if (future == null) {
            log.warn("[Correlation] 无对应的等待（可能已超时）: correlationId={}",
                    correlationId);
            return false;
        }
        boolean ok = future.complete(message);
        if (log.isDebugEnabled()) {
            log.debug("[Correlation] 完成: correlationId={}, ok={}",
                    correlationId, ok);
        }
        return ok;
    }

    /**
     * 取消一个等待——超时或异常时调用
     */
    public void cancel(String correlationId) {
        if (correlationId == null) {
            return;
        }
        CompletableFuture<AgentMessage> future = pending.remove(correlationId);
        if (future != null) {
            future.completeExceptionally(
                    new IllegalStateException("等待被取消: " + correlationId));
            log.debug("[Correlation] 取消: correlationId={}", correlationId);
        }
    }

    /**
     * 当前挂起数量——监控/测试用
     */
    public int pendingCount() {
        return pending.size();
    }
}