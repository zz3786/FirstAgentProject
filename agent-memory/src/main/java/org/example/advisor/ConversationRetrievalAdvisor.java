package org.example.advisor;

import lombok.extern.slf4j.Slf4j;
import org.example.common.utils.TextUtils;
import org.example.memory.ConversationMemoryService;
import org.example.common.utils.ConversationIdUtils;
import org.example.common.utils.PromptUtils;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * D50 对话历史检索 Advisor（读路径）
 * <p>
 * <b>执行顺序</b>：order = 210
 * <pre>
 * MessageChatMemoryAdvisor
 *   → CompactingChatMemoryAdvisor(50)
 *   → PreferenceAdvisor(100)
 *   → RagAdvisor(150)
 *   → MemoryRetrievalAdvisor(200)        ← LTM 关键词路
 *   → ConversationRetrievalAdvisor(210)  ← D50 向量语义路（本类）
 *   → ToolLoggingAdvisor
 * </pre>
 * <p>
 * <b>为什么排在 MemoryRetrievalAdvisor 之后</b>：
 * 先让 LTM 关键词路召回精炼事实，再让 D50 补语义模糊的历史原文，
 * 两条结果会合并在 SystemMessage 里，模型能同时看到"要点"和"原话"。
 * <p>
 * <b>与 RagAdvisor 的定位区分</b>：
 * RagAdvisor 检索的是**知识库文档**（企业制度、合同等），
 * 本 Advisor 检索的是**用户自己的历史对话**——两者互不干扰。
 */
@Slf4j
public class ConversationRetrievalAdvisor implements CallAdvisor, StreamAdvisor {

    private final ConversationMemoryService memoryService;

    public ConversationRetrievalAdvisor(ConversationMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String getName() {
        return "ConversationRetrievalAdvisor";
    }

    @Override
    public int getOrder() {
        return 210;
    }

    // ==================== 同步 ====================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(enrich(request));
    }

    // ==================== 流式 ====================

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        log.info("[ENTER] {} order={}", getName(), getOrder());
        return chain.nextStream(enrich(request))
                .doOnComplete(() -> log.info("[EXIT]  {} order={}", getName(), getOrder()));
    }

    // ==================== 核心：注入历史对话 ====================

    private ChatClientRequest enrich(ChatClientRequest request) {
        // ① 拿会话 ID
        Object cid = request.context().get(ChatMemory.CONVERSATION_ID);
        if (cid == null) {
            return request;
        }
        String conversationId = cid.toString();
        String fullUserId = ConversationIdUtils.extractFullUserId(cid.toString());

        // ② 取最后一条用户消息作为检索 query
        String query = lastUserMessage(request);
        if (query == null || query.isBlank()) {
            return request;
        }

        // ③ 检索历史轮次
        List<Document> hits = memoryService.search(fullUserId, query, conversationId);
        if (hits.isEmpty()) {
            return request;
        }

        // ④ 拼装注入文本
        String injection = buildInjection(hits);
        log.info("历史对话向量库（conv-mon） 注入 {} 条历史对话: userId={}, query=[{}]",
                hits.size(), fullUserId, TextUtils.truncate(query, 30));

        return PromptUtils.appendSystemMessage(request, injection);
    }

    /**
     * 构造注入文本
     * <p>
     * 格式上和 MemoryRetrievalAdvisor 保持一致的风格——
     * 编号 + "用户曾说/你当时回答"清晰区分角色。
     */
    private String buildInjection(List<Document> hits) {
        StringBuilder sb = new StringBuilder("相关历史对话（供参考，不是当前会话）：\n");
        for (int i = 0; i < hits.size(); i++) {
            Document d = hits.get(i);
            Object userMsg = d.getMetadata().get("user_message");
            Object assistantMsg = d.getMetadata().get("assistant_message");

            sb.append(i + 1).append(". 用户曾说：")
                    .append(userMsg == null ? "" : userMsg).append("\n");
            sb.append("   你当时回答：")
                    .append(TextUtils.truncate(assistantMsg == null ? "" : assistantMsg.toString(), 200))
                    .append("\n");
        }
        return sb.toString();
    }

    // ==================== 辅助 ====================
    private String lastUserMessage(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .filter(m -> "USER".equals(m.getMessageType().name()))
                .map(Message::getText)
                .reduce((a, b) -> b)
                .orElse(null);
    }
}