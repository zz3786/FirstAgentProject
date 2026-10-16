package org.example.multiagent.worker;

import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.example.core.rbac.ToolAuthorizer;
import org.example.core.toolprofile.ToolProfileResolver;
import org.example.core.tools.SafeToolCallbackFactory;
import org.example.multiagent.config.WorkerConfig;
import org.example.multiagent.config.WorkerProperties;
import org.example.multiagent.model.AgentRole;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 法务专家 Worker（D73）
 *
 * <h3>工具集</h3>
 * <p>
 * ToolProfile {@code worker-legal}——getOrderStatus / analyzeText /
 * saveLongTermMemory / mcp_getOrderStatus
 * <p>
 * <b>注意</b>：法务 Worker 只用<b>只读工具</b>——不给 createTodo、不给 calculate。
 * 法务不该写待办，也不该做算术。这是"最小权限原则"的落地。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "app.workers.legal.role",
        havingValue = "LEGAL",
        matchIfMissing = false
)
public class LegalWorkerAgent extends AbstractWorkerAgent {

    private final ChatClient legalChatClient;

    public LegalWorkerAgent(
            @Qualifier("legalWorkerClient") ChatClient legalChatClient,
            ToolProfileResolver toolProfileResolver,
            SafeToolCallbackFactory safeToolCallbackFactory,
            ToolAuthorizer toolAuthorizer,
            WorkerProperties workerProperties,
            ObjectProvider<Tracer> tracerProvider) {

        super(legalChatClient,
                toolProfileResolver,
                safeToolCallbackFactory,
                toolAuthorizer,
                resolveConfig(workerProperties),
                tracerProvider);

        this.legalChatClient = legalChatClient;
    }

    private static WorkerConfig resolveConfig(WorkerProperties props) {
        WorkerConfig cfg = props.findByName("legal");
        if (cfg == null) {
            throw new IllegalStateException(
                    "app.workers.legal 未配置——请检查 app-multi-agent.yml");
        }
        return cfg;
    }

    @Override
    public AgentRole role() {
        return AgentRole.LEGAL;
    }

    @Override
    protected ChatClient chatClient() {
        return legalChatClient;
    }

    @Override
    protected String consumerName() {
        return "worker-legal";
    }
}