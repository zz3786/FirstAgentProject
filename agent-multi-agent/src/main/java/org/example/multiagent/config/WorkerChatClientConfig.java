package org.example.multiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.model.AgentRole;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Worker ChatClient 装配（D73）
 *
 * <h3>装配 3 个 Client</h3>
 * <pre>
 *   OpenAiChatModel
 *      │
 *      ├──→ financialWorkerClient   defaultSystem = app.workers.financial.system-prompt
 *      ├──→ technicalWorkerClient   defaultSystem = app.workers.technical.system-prompt
 *      └──→ legalWorkerClient       defaultSystem = app.workers.legal.system-prompt
 * </pre>
 *
 * <h3>为什么不带 Advisor</h3>
 * <p>
 * 和 D72 修订版的原则一致——Worker 输出是中间产物，
 * 不需要记忆、RAG、反思。那些放到 Supervisor 汇总层。
 * <ul>
 *   <li>没有 MessageChatMemoryAdvisor——Worker 不需要对话历史</li>
 *   <li>没有 RagAdvisor——Worker 只做"专业判断"，不做检索</li>
 *   <li>没有 SelfReflectionAdvisor——汇总层再反思</li>
 * </ul>
 *
 * <h3>工具为什么不在这里装配</h3>
 * <p>
 * 工具在 {@code AbstractWorkerAgent.execute()} 里每次动态取——
 * 支持 D68 的运行时刷新。
 * <p>
 * 本类只负责"配置 System Prompt"——这是 Worker 的静态身份。
 *
 * <h3>为什么 defaultSystem 从 yml 读而不是硬编码</h3>
 * <p>
 * System Prompt 是"领域专家人设"，属于<b>业务配置</b>而非代码逻辑：
 * <ul>
 *   <li>运营/产品可能想调整语气——不该改代码重新部署</li>
 *   <li>不同客户可能需要不同的专家人设——yml 便于定制</li>
 *   <li>Prompt 迭代频繁——yml 改完重启即可</li>
 * </ul>
 *
 * <h3>如果某个 Worker 未配置</h3>
 * <p>
 * 三种情况：
 * <ol>
 *   <li>yml 里 `app.workers.financial` 缺失 → 本 Bean 不注册 → FinancialWorkerAgent 因缺 @Qualifier 而启动失败</li>
 *   <li>yml 有但 systemPrompt 为空 → 用兜底 Prompt</li>
 *   <li>yml 有但 role 不对 → WorkerAgent 的 @ConditionalOnProperty 不匹配 → 不注册</li>
 * </ol>
 */
@Slf4j
@Configuration
public class WorkerChatClientConfig {

    /** 兜底 System Prompt——yml 配置缺失时用 */
    private static final String FALLBACK_PROMPT = "你是一位可靠的专业助手。";

    @Bean("financialWorkerClient")
    public ChatClient financialWorkerClient(
            OpenAiChatModel chatModel,
            WorkerProperties workerProperties) {
        WorkerConfig cfg = workerProperties.findByRole(AgentRole.FINANCIAL);
        return buildClient(chatModel, cfg, "financialWorkerClient", AgentRole.FINANCIAL);
    }

    @Bean("technicalWorkerClient")
    public ChatClient technicalWorkerClient(
            OpenAiChatModel chatModel,
            WorkerProperties workerProperties) {
        WorkerConfig cfg = workerProperties.findByRole(AgentRole.TECHNICAL);
        return buildClient(chatModel, cfg, "technicalWorkerClient", AgentRole.TECHNICAL);
    }

    @Bean("legalWorkerClient")
    public ChatClient legalWorkerClient(
            OpenAiChatModel chatModel,
            WorkerProperties workerProperties) {
        WorkerConfig cfg = workerProperties.findByRole(AgentRole.LEGAL);
        return buildClient(chatModel, cfg, "legalWorkerClient", AgentRole.LEGAL);
    }

    /**
     * 构造单个 Worker Client
     * <p>
     * 抽出来是因为 3 个 Client 逻辑完全相同，只是 role 和 bean 名不同。
     */
    private ChatClient buildClient(OpenAiChatModel chatModel,
                                   WorkerConfig cfg,
                                   String beanName,
                                   AgentRole role) {
        String systemPrompt;
        if (cfg == null || cfg.getSystemPrompt() == null || cfg.getSystemPrompt().isBlank()) {
            systemPrompt = FALLBACK_PROMPT;
            log.warn("[{}] yml 未配置 system-prompt，使用兜底 Prompt（role={}）",
                    beanName, role);
        } else {
            systemPrompt = cfg.getSystemPrompt();
        }

        log.info("[{}] 装配: role={}, promptLen={}",
                beanName, role, systemPrompt.length());

        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .build();
    }
}