package org.example.multiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.reflection.SelfReflectionAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多智能体协作的 Bean 装配（D71）
 *
 * <h3>装配的 3 个 Bean</h3>
 * <pre>
 *   OpenAiChatModel（Spring AI 自动配置）
 *         │
 *         ├──→ reflectorChatClient      无 Advisor，用于质量评审
 *         │
 *         └──→ reflectiveChatClient     带 SelfReflectionAdvisor，用于主调用
 * </pre>
 *
 * <h3>为什么 reflectorChatClient 不带任何 Advisor</h3>
 * <p>
 * 评审员需要"干净的视野"——只看问答对，不看记忆/RAG/偏好。
 * 带了 Advisor 反而会让评审员被注入内容误导。
 *
 * <h3>为什么 reflectiveChatClient 只带 SelfReflectionAdvisor</h3>
 * <p>
 * D71 阶段这是最简配置——用来验证反思逻辑能跑。
 * <ul>
 *   <li>D73 会为每个 Worker 装配各自的 ChatClient（带 ToolProfile）</li>
 *   <li>D77 会加 TracingAdvisor</li>
 *   <li>记忆/RAG 是否要给 Worker 用，D73 决策时再加</li>
 * </ul>
 *
 * <h3>Bean 命名规范</h3>
 * <p>
 * 前缀 {@code reflector*} / {@code reflective*}——与 {@code agent-core}
 * 里的 {@code redisChatClient} / {@code plainChatClient} / {@code summarizerClient}
 * 风格一致，避免 Bean 名冲突。
 */
@Slf4j
@Configuration
public class MultiAgentBeanConfig {

    /**
     * 评审员 Client——用于反思阶段的质量评估
     * <p>
     * <b>System Prompt 固定</b>：评审员的角色不该被业务 Prompt 影响。
     * 具体的评估维度在 {@code SelfReflectionAdvisor.buildReflectionPrompt()} 里通过
     * user message 传入——这个 defaultSystem 只定义"身份"。
     */
    @Bean("reflectorChatClient")
    public ChatClient reflectorChatClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一位严格但公正的 AI 回答质量评审员。")
                .build();
    }

    /**
     * 反思 Advisor——核心 Bean
     * <p>
     * 显式装配（不用 @Component）的理由与 D69 的 RbacToolAuthorizer 一致：
     * <ul>
     *   <li>装配时打日志，启动就能看到配置是否生效</li>
     *   <li>未来要换实现（如加"多评审员投票"），只改这里</li>
     *   <li>Advisor 是"纯逻辑"类，便于单测 new 出来</li>
     * </ul>
     */
    @Bean
    public SelfReflectionAdvisor selfReflectionAdvisor(
            @Qualifier("reflectorChatClient") ChatClient reflectorChatClient,
            MultiAgentProperties props) {
        MultiAgentProperties.Reflection cfg = props.getReflection();
        log.info("[MultiAgent] 装配 SelfReflectionAdvisor: enabled={}, maxRounds={}, threshold={}",
                cfg.isEnabled(), cfg.getMaxRounds(), cfg.getQualityThreshold());
        return new SelfReflectionAdvisor(reflectorChatClient, props);
    }

    /**
     * 主调用 Client——带反思能力
     * <p>
     * D71 阶段 {@code MultiAgentFacade} 唯一使用的 Client。
     * D73 会被每个 Worker 独立的 Client 替代/扩展。
     */
    @Bean("reflectiveChatClient")
    public ChatClient reflectiveChatClient(
            OpenAiChatModel chatModel,
            SelfReflectionAdvisor selfReflectionAdvisor) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(selfReflectionAdvisor)
                .build();
    }
}