package org.example.config;

import org.example.advisor.CompactingChatMemoryAdvisor;
import org.example.advisor.MemoryRetrievalAdvisor;
import org.example.advisor.PreferenceAdvisor;
import org.example.advisor.ToolLoggingAdvisor;
import org.example.memory.LongTermMemoryService;
import org.example.preference.UserPreferenceService;
import org.example.rag.advisor.RagAdvisor;
import org.example.rag.config.RagProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.vectorstore.VectorStore;
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
            VectorStore vectorStore,
            RagProperties ragProperties) {          // ← 新增参数

        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        // ① 会话记忆
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        // ② 压缩
                        compactingAdvisor,
                        // ③ 用户偏好
                        new PreferenceAdvisor(preferenceService),
                        // ④ RAG 检索（topK、阈值从配置读）
                        new RagAdvisor(vectorStore, ragProperties),   // ← 改这里
                        // ⑤ 长期记忆检索
                        new MemoryRetrievalAdvisor(longTermMemoryService),
                        // ⑥ 工具日志
                        new ToolLoggingAdvisor()
                )
                .build();
    }

    /**
     * 无记忆 Client：用于简单同步 / 流式调用，不带记忆、不带工具
     */
    @Bean("plainChatClient")
    public ChatClient plainChatClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}