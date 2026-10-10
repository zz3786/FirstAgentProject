package org.example.multiagent.worker;

import lombok.extern.slf4j.Slf4j;
import org.example.core.rbac.ToolAuthorizer;
import org.example.core.toolprofile.ToolProfileResolver;
import org.example.core.tools.SafeToolCallbackFactory;
import org.example.multiagent.config.WorkerConfig;
import org.example.multiagent.config.WorkerProperties;
import org.example.multiagent.model.AgentRole;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 技术专家 Worker（D73）
 *
 * <h3>工具集</h3>
 * <p>
 * ToolProfile {@code worker-technical}——calculate / analyzeText /
 * createTodo / saveLongTermMemory / mcp_calculate
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "app.workers.technical.role",
        havingValue = "TECHNICAL",
        matchIfMissing = false
)
public class TechnicalWorkerAgent extends AbstractWorkerAgent {

    private final ChatClient technicalChatClient;

    public TechnicalWorkerAgent(
            @Qualifier("technicalWorkerClient") ChatClient technicalChatClient,
            ToolProfileResolver toolProfileResolver,
            SafeToolCallbackFactory safeToolCallbackFactory,
            ToolAuthorizer toolAuthorizer,
            WorkerProperties workerProperties) {

        super(technicalChatClient,
                toolProfileResolver,
                safeToolCallbackFactory,
                toolAuthorizer,
                resolveConfig(workerProperties));

        this.technicalChatClient = technicalChatClient;
    }

    private static WorkerConfig resolveConfig(WorkerProperties props) {
        WorkerConfig cfg = props.findByName("technical");
        if (cfg == null) {
            throw new IllegalStateException(
                    "app.workers.technical 未配置——请检查 app-multi-agent.yml");
        }
        return cfg;
    }

    @Override
    public AgentRole role() {
        return AgentRole.TECHNICAL;
    }

    @Override
    protected ChatClient chatClient() {
        return technicalChatClient;
    }

    @Override
    protected String consumerName() {
        return "worker-technical";
    }
}