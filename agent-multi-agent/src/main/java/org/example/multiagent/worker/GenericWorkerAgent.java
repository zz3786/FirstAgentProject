package org.example.multiagent.worker;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 通用 Worker（D72 临时方案）
 *
 * <h3>D72 修订：去掉反思</h3>
 * <p>
 * 首版误用了 D71 的 {@code reflectiveChatClient}——导致单子任务耗时 57 秒。
 * 修订后改用 {@code workerBaseClient}（无 Advisor）：
 * <ul>
 *   <li>单子任务耗时从 ~57 秒降到 ~28 秒</li>
 *   <li>整体 3 子任务耗时从"超时"降到 ~30 秒（并行）</li>
 *   <li>反思能力保留在最终汇总层（如果需要）</li>
 * </ul>
 *
 * <h3>关于 {@code role()} 返回值</h3>
 * <p>
 * 见 D72 首版注释——返回 {@code SUPERVISOR} 作为"兜底 Worker"的特殊标记，
 * {@code WorkerAgentRegistry} 会识别这个值。
 * <h3>D73 更新</h3>
 * <p>
 * D73 加入了 3 个真正的领域 Worker（{@code FinancialWorkerAgent} /
 * {@code TechnicalWorkerAgent} / {@code LegalWorkerAgent}）。
 * 本类的 {@code @ConditionalOnMissingBean} 会自动生效——3 个真实现注册后，
 * 本类不再注册。
 *
 * <p>
 * <b>未来扩展新角色时</b>：如果新增的角色（如 {@code HR}）暂时没有专属 Worker，
 * 本类会再次自动启用（因为 {@code WorkerAgent} 类型的 Bean 里没有 HR 的专属实现，
 * 但会有其他角色的实现——此处 {@code @ConditionalOnMissingBean} 的语义要谨慎）。
 *
 * <p>
 * <b>注意</b>：{@code @ConditionalOnMissingBean} 的默认语义是"容器里没有任何
 * WorkerAgent 实现时才注册本类"。D73 加 3 个真实现后本类永久禁用。
 * 若未来需要"per-role 兜底"，应改为在 {@code WorkerAgentRegistry} 里做兜底
 * （当前已经实现——{@code registry.get(role)} 找不到时返回 fallback）。
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = WorkerAgent.class, ignored = GenericWorkerAgent.class)
public class GenericWorkerAgent implements WorkerAgent {

    private final ChatClient workerBaseClient;

    public GenericWorkerAgent(
            @Qualifier("workerBaseClient") ChatClient workerBaseClient) {
        this.workerBaseClient = workerBaseClient;
    }

    @Override
    public AgentRole role() {
        return AgentRole.SUPERVISOR;
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        long start = System.currentTimeMillis();

        try {
            // ★ 用 workerBaseClient——无 Advisor、无反思
            String answer = workerBaseClient.prompt()
                    .user(request.input())
                    .call()
                    .content();

            long cost = System.currentTimeMillis() - start;

            if (answer == null || answer.isBlank()) {
                log.warn("[GenericWorker] 返回空答案: role={}, requestId={}, costMs={}",
                        request.role(), request.requestId(), cost);
                return AgentResponse.fail(
                        request.requestId(), request.role(),
                        "Worker 返回空答案", cost);
            }

            log.debug("[GenericWorker] 完成: role={}, requestId={}, costMs={}",
                    request.role(), request.requestId(), cost);

            // 无反思——reflectionRounds=0, qualityScore=-1（未评估）
            return AgentResponse.ok(
                    request.requestId(), request.role(),
                    answer, cost, 0, -1.0);

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("[GenericWorker] 失败: role={}, requestId={}, err={}",
                    request.role(), request.requestId(), e.getMessage());
            return AgentResponse.fail(
                    request.requestId(), request.role(),
                    "Worker 执行失败: " + e.getMessage(), cost);
        }
    }
}