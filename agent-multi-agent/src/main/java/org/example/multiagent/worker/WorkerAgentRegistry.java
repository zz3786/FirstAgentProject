package org.example.multiagent.worker;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.model.AgentRole;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Worker 注册中心（D72）
 *
 * <h3>职责</h3>
 * <p>
 * 在启动时收集所有 {@link WorkerAgent} Bean，按 {@code role()} 建索引。
 * 运行时，{@code TaskDispatcher} 通过 {@link #get(AgentRole)} 找对应 Worker。
 *
 * <h3>为什么注入 {@code List<WorkerAgent>} 而不是 {@code Map<String, WorkerAgent>}</h3>
 * <p>
 * 和 {@code WorkflowNodeRegistry} 同样的理由——
 * Spring 对 {@code Map<String, T>} 有"集合注入特殊规则"——
 * key 会被替换成 bean name，导致我们自定义的 {@code role()} 作为 key 失效。
 * <p>
 * 改为注入 List，手动组装 Map——key 完全由我们掌控。
 *
 * <h3>兜底机制</h3>
 * <p>
 * 如果某个角色没有专属 Worker，用 {@code GenericWorkerAgent} 兜底。
 * 兜底 Worker 的 {@code role()} 返回 {@code SUPERVISOR}——本类识别这个值。
 *
 * <h3>重复角色保护</h3>
 * <p>
 * 如果两个 Worker 声明了同一个 role——注册时抛异常。
 * 防止 D73 加入真实现时忘了禁用 {@code GenericWorkerAgent}。
 */
@Slf4j
@Component
public class WorkerAgentRegistry {

    private final Map<AgentRole, WorkerAgent> workers = new EnumMap<>(AgentRole.class);
    private final WorkerAgent fallback;

    public WorkerAgentRegistry(List<WorkerAgent> agentList) {
        WorkerAgent fallbackCandidate = null;

        for (WorkerAgent agent : agentList) {
            AgentRole role = agent.role();
            if (role == AgentRole.SUPERVISOR) {
                // 特殊标记——这是兜底 Worker，不进入常规索引
                fallbackCandidate = agent;
                continue;
            }
            WorkerAgent existing = workers.putIfAbsent(role, agent);
            if (existing != null) {
                throw new IllegalStateException(
                        "Worker 角色冲突: role=" + role
                                + " 同时被 " + existing.getClass().getSimpleName()
                                + " 和 " + agent.getClass().getSimpleName() + " 声明");
            }
        }

        this.fallback = fallbackCandidate;

        log.info("[WorkerAgentRegistry] 初始化：专属 Worker {} 个 {}，兜底 Worker={}",
                workers.size(),
                workers.keySet(),
                fallback == null ? "无" : fallback.getClass().getSimpleName());
    }

    /**
     * 按角色取 Worker。
     * <p>
     * 命中专属 Worker 返回它；否则返回兜底 Worker（可能为 null）。
     *
     * @return Worker；找不到任何可用 Worker 时返回 {@code Optional.empty()}
     */
    public Optional<WorkerAgent> get(AgentRole role) {
        if (role == null) {
            return Optional.ofNullable(fallback);
        }
        WorkerAgent agent = workers.get(role);
        if (agent != null) {
            return Optional.of(agent);
        }
        return Optional.ofNullable(fallback);
    }

    /**
     * 是否存在专属 Worker（不含兜底）
     */
    public boolean has(AgentRole role) {
        return workers.containsKey(role);
    }

    /** 所有已注册的角色 */
    public java.util.Set<AgentRole> registeredRoles() {
        return workers.keySet();
    }

    /** 是否有兜底 Worker */
    public boolean hasFallback() {
        return fallback != null;
    }
}