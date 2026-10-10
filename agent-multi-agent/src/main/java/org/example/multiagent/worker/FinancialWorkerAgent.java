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
 * 财务专家 Worker（D73）
 *
 * <h3>角色定义</h3>
 * <p>
 * 从 yml 的 {@code app.workers.financial.system-prompt} 读取——
 * 系统提示词决定"这个 Agent 的领域身份和表达风格"。
 *
 * <h3>工具集</h3>
 * <p>
 * 用 ToolProfile {@code worker-financial}——在 app-tool-profiles.yml 定义。
 * 包含：calculate / getOrderStatus / createTodo / analyzeText /
 *       saveLongTermMemory / mcp_calculate / mcp_getOrderStatus
 *
 * <h3>为什么用 @ConditionalOnProperty</h3>
 * <p>
 * 允许通过 yml 关闭某个 Worker——便于灰度、故障隔离、A/B 测试。
 * 关闭后 Supervisor 找不到该角色的 Worker，会走 GenericWorkerAgent 兜底
 * （前提是 GenericWorkerAgent 也启用）。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "app.workers.financial.role",
        havingValue = "FINANCIAL",
        matchIfMissing = false
)
public class FinancialWorkerAgent extends AbstractWorkerAgent {

    private final ChatClient financialChatClient;

    public FinancialWorkerAgent(
            @Qualifier("financialWorkerClient") ChatClient financialChatClient,
            ToolProfileResolver toolProfileResolver,
            SafeToolCallbackFactory safeToolCallbackFactory,
            ToolAuthorizer toolAuthorizer,
            WorkerProperties workerProperties) {

        super(financialChatClient,
                toolProfileResolver,
                safeToolCallbackFactory,
                toolAuthorizer,
                resolveConfig(workerProperties));

        this.financialChatClient = financialChatClient;
    }

    private static WorkerConfig resolveConfig(WorkerProperties props) {
        WorkerConfig cfg = props.findByName("financial");
        if (cfg == null) {
            throw new IllegalStateException(
                    "app.workers.financial 未配置——请检查 app-multi-agent.yml");
        }
        return cfg;
    }

    @Override
    public AgentRole role() {
        return AgentRole.FINANCIAL;
    }

    @Override
    protected ChatClient chatClient() {
        return financialChatClient;
    }

    @Override
    protected String consumerName() {
        return "worker-financial";
    }
}