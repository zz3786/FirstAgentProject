package org.example.core.chat.config;

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
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 装配配置
 * <p>
 * 把「ChatClient 的构建」从 Service 层抽到配置层，
 * Service 只负责注入现成的 Client，专注业务逻辑。
 */
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
     * 主业务 Client：带记忆 + 压缩 + 偏好 + 长期记忆检索 + 工具日志
     * <p>
     * Advisor 执行顺序由 getOrder() 决定：
     * <ol>
     *     <li>MessageChatMemoryAdvisor（order 极小）</li>
     *     <li>CompactingChatMemoryAdvisor（order=50）</li>
     *     <li>PreferenceAdvisor（order=100）</li>
     *     <li>MemoryRetrievalAdvisor（order=200）</li>
     *     <li>ToolLoggingAdvisor</li>
     * </ol>
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
            ObjectProvider<TracingAdvisor> tracingAdvisorProvider) {   // ★ 新增

        // ★ D77：TracingAdvisor 可能不存在（依赖缺失时）——可选注入
        TracingAdvisor tracingAdvisor = tracingAdvisorProvider.getIfAvailable();

        // ★ 临时调试——看 Advisor 到底有没有拿到
        System.out.println(">>> [ChatClientConfig] tracingAdvisor = " + tracingAdvisor
                + " (class=" + (tracingAdvisor == null ? "null" : tracingAdvisor.getClass().getSimpleName()) + ")");

        // 构造 Advisor 数组——根据 tracingAdvisor 是否存在动态决定
        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        // ① D77：TracingAdvisor——最外层
                        tracingAdvisor != null ? tracingAdvisor : noopAdvisor(),
                        // ② 会话记忆
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        // ③ 压缩
                        compactingAdvisor,
                        // ④ 用户偏好
                        new PreferenceAdvisor(preferenceService),
                        // ⑤ 用户画像
                        userInterestAdvisor,
                        // ⑥ RAG 检索
                        new RagAdvisor(hybridSearchService),
                        // ⑦ LTM 关键词路
                        new LongTermMemoryAdvisor(longTermMemoryService),
                        // ⑧ 对话历史向量路
                        conversationRetrievalAdvisor,
                        // ⑨ 工具日志
                        new ToolLoggingAdvisor(),
                        // ⑩ 对话历史写入
                        conversationMemoryAdvisor,
                        // ⑪ 提示词日志
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