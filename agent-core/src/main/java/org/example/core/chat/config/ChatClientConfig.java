package org.example.core.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.example.advisor.*;
import org.example.core.chat.advisor.DebugPromptAdvisor;
import org.example.core.chat.advisor.ToolLoggingAdvisor;
import org.example.memory.LongTermMemoryService;
import org.example.observability.tracing.TracingAdvisor;
import org.example.preference.UserPreferenceService;
import org.example.rag.retrieval.advisor.RagAdvisor;
import org.example.rag.retrieval.service.HybridSearchService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * ChatClient 装配配置
 * <p>
 * 把「ChatClient 的构建」从 Service 层抽到配置层，
 * Service 只负责注入现成的 Client，专注业务逻辑。
 */
@Slf4j
@Configuration
public class ChatClientConfig {

    /**
     * 摘要专用 Client
     * <p>
     * 只用于 CompactingChatMemoryAdvisor 内部生成摘要，
     * 不加任何 Advisor，避免与主 Client 循环依赖。
     */
    @Bean("summarizerClient")
    public ChatClient summarizerClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是对话摘要助手，擅长把冗长对话压缩为简洁要点。")
                .build();
    }

    /**
     * 主业务 Client：带安全防护 + 记忆 + 压缩 + 偏好 + 长期记忆检索 + 工具日志
     */
    @Bean("redisChatClient")
    public ChatClient redisChatClient(
            OpenAiChatModel chatModel,
            @Qualifier("redisChatMemory") ChatMemory chatMemory,
            CompactingChatMemoryAdvisor compactingAdvisor,
            UserPreferenceService preferenceService,
            LongTermMemoryService longTermMemoryService,
            HybridSearchService hybridSearchService,
            ConversationRetrievalAdvisor conversationRetrievalAdvisor,
            ConversationMemoryAdvisor conversationMemoryAdvisor,
            UserInterestAdvisor userInterestAdvisor,
            ObjectProvider<TracingAdvisor> tracingAdvisorProvider) {

        TracingAdvisor tracingAdvisor = tracingAdvisorProvider.getIfAvailable();

        // ★ 1. 初始化安全防护 Advisor（生产环境请替换为实际的违规词库或从 yml 读取）
        // 默认拦截提示词为："I'm unable to respond to that."
        // 这里你可以自定义拦截后返回给用户的友好提示
        SafeGuardAdvisor safeGuardAdvisor = new SafeGuardAdvisor(
                List.of("暴力", "色情", "政治", "忽略之前的指令", "ignore previous instructions")
        );

        log.info("[ChatClientConfig] 已装配 SafeGuardAdvisor 安全拦截");

        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        // ① D81：安全防护——必须在最外层，违规输入直接短路，不消耗后续 Token
                        safeGuardAdvisor,

                        // ② D77：TracingAdvisor——链路追踪
                        tracingAdvisor != null ? tracingAdvisor : noopAdvisor(),

                        // ③ 会话记忆
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),

                        // ④ 压缩
                        compactingAdvisor,

                        // ⑤ 用户偏好
                        new PreferenceAdvisor(preferenceService),

                        // ⑥ 用户画像
                        userInterestAdvisor,

                        // ⑦ RAG 检索
                        new RagAdvisor(hybridSearchService),

                        // ⑧ LTM 关键词路
                        new LongTermMemoryAdvisor(longTermMemoryService),

                        // ⑨ 对话历史向量路
                        conversationRetrievalAdvisor,

                        // ⑩ 工具日志
                        new ToolLoggingAdvisor(),

                        // ⑪ 对话历史写入
                        conversationMemoryAdvisor,

                        // ⑫ 提示词日志
                        new DebugPromptAdvisor()
                )
                .build();
    }

    /**
     * 无记忆 Client：用于简单同步 / 流式调用，不带记忆、不带工具
     */
    @Bean("plainChatClient")
    public ChatClient plainChatClient(
            OpenAiChatModel chatModel,
            ObjectProvider<TracingAdvisor> tracingAdvisorProvider) {
        TracingAdvisor tracingAdvisor = tracingAdvisorProvider.getIfAvailable();
        if (tracingAdvisor != null) {
            return ChatClient.builder(chatModel)
                    .defaultAdvisors(tracingAdvisor)
                    .build();
        }
        return ChatClient.builder(chatModel).build();
    }




    /**
     * 空 Advisor——当 TracingAdvisor 不存在时的占位
     * <p>
     * {@code ChatClient.defaultAdvisors(...)} 不接受 null——
     * 用这个空实现保持 Advisor 数量一致（虽然功能上不必要）。
     * <p>
     * <b>更简方案</b>：把 {@code defaultAdvisors} 拆成两个分支——
     * 有/无 TracingAdvisor 各调一次。但那会重复一堆代码。
     * 空 Advisor 更简洁。
     */
    private static org.springframework.ai.chat.client.advisor.api.CallAdvisor noopAdvisor() {
        return new org.springframework.ai.chat.client.advisor.api.CallAdvisor() {
            @Override
            public String getName() {
                return "NoopAdvisor";
            }

            @Override
            public int getOrder() {
                return org.springframework.core.Ordered.HIGHEST_PRECEDENCE;
            }

            @Override
            public org.springframework.ai.chat.client.ChatClientResponse adviseCall(
                    org.springframework.ai.chat.client.ChatClientRequest request,
                    org.springframework.ai.chat.client.advisor.api.CallAdvisorChain chain) {
                return chain.nextCall(request);
            }
        };
    }
}