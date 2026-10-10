package org.example.multiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Worker 配置容器（D73）
 *
 * <h3>为什么继承 LinkedHashMap 而不是包一层</h3>
 * <p>
 * yml 结构是：
 * <pre>
 * app:
 *   workers:
 *     financial: {...}
 *     technical: {...}
 *     legal: {...}
 * </pre>
 * <p>
 * 如果写成 {@code private Map<String, WorkerConfig> workers}，
 * 那么 Spring Boot 会去 {@code app.workers.workers.*} 找配置——多了一层。
 * <p>
 * 直接继承 {@code LinkedHashMap<String, WorkerConfig>} 让本类本身成为 Map——
 * {@code prefix = "app.workers"} + Map 的 key 就是 financial/technical/legal。
 * 这是 Spring Boot 官方支持的写法。
 *
 * <h3>为什么用 LinkedHashMap 而非 HashMap</h3>
 * <p>
 * 保持 yml 里的声明顺序——启动日志里 Worker 按声明顺序打印更易读。
 *
 * <h3>为什么不用 @Validated</h3>
 * <p>
 * 校验在 {@code AbstractWorkerAgent} 构造时做——那里能看到具体的 worker 名，
 * 报错信息更具体（"worker=financial 缺 tool-profile"而非"某个字段为 null"）。
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "app.workers")
public class WorkerProperties extends LinkedHashMap<String, WorkerConfig> {

    /**
     * 按角色查找——由 {@code WorkerChatClientConfig} 装配时使用。
     *
     * @param role Worker 角色
     * @return 对应配置；找不到返回 {@code null}
     */
    public WorkerConfig findByRole(org.example.multiagent.model.AgentRole role) {
        return values().stream()
                .filter(c -> c.getRole() == role)
                .findFirst()
                .orElse(null);
    }

    /**
     * 按 worker 名（yml key）查找。
     *
     * @param name yml 里的 key（financial / technical / legal）
     */
    public WorkerConfig findByName(String name) {
        return get(name);
    }

    /**
     * 打印配置摘要——启动时用于确认 yml 被正确加载。
     */
    public void logSummary() {
        if (isEmpty()) {
            log.warn("[WorkerProperties] app.workers 未配置任何 Worker");
            return;
        }
        log.info("[WorkerProperties] 加载 {} 个 Worker 配置:", size());
        forEach((name, cfg) -> log.info("  ├─ {} → role={}, profile={}, promptLen={}",
                name, cfg.getRole(), cfg.getToolProfile(),
                cfg.getSystemPrompt() == null ? 0 : cfg.getSystemPrompt().length()));
    }
}