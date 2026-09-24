package org.example.rag.config;


import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 相关 Bean 装配
 * <p>
 * 如果 application.yml 已配置 spring.ai.vectorstore.qdrant.*，
 * 则不需要手动定义 VectorStore Bean（自动配置已生效）。
 * 手动定义仅用于覆盖默认行为（如自定义 collection 名称）。
 */
@Configuration
public class RagConfig {

    /**
     * 创建并配置 QuestionAnswerAdvisor
     * 它负责在对话时自动从 VectorStore 检索相关文档
     */
    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        // 1. 构建检索请求
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(5)                         // 返回最相似的5个文档片段
                .similarityThreshold(0.7)        // 相似度阈值，低于此值的片段会被过滤掉
                .build();

        // 2. 创建 Advisor
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .build();
    }
}