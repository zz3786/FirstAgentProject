package org.example.memory;

import lombok.extern.slf4j.Slf4j;
import org.example.config.ConversationMemoryProperties;
import org.example.utils.SensitiveDataMasker;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * D50 对话历史向量化服务
 * <p>
 * <b>职责</b>：把每一轮对话（user + assistant）向量化后存入 Redis 向量库，
 * 并提供"按当前问题语义检索历史轮次"的能力。
 * <p>
 * <b>与 LongTermMemoryService 的定位区分</b>：
 * <pre>
 * ┌──────────────┬────────────────────┬────────────────────┐
 * │              │ LTM（关键词）      │ D50（向量）        │
 * ├──────────────┼────────────────────┼────────────────────┤
 * │ 写入时机     │ 模型主动 save      │ 每轮自动           │
 * │ 存储内容     │ 精炼事实一句话     │ 原始对话原文       │
 * │ 检索方式     │ 中文 2-gram 关键词 │ 向量语义           │
 * │ 类比         │ 笔记本上的"要点"   │ 完整录音档案       │
 * └──────────────┴────────────────────┴────────────────────┘
 * 两者互补共存，不替换。
 */
@Slf4j
@Service
public class ConversationMemoryService {

    private final VectorStore conversationVectorStore;
    private final ConversationMemoryProperties props;

    public ConversationMemoryService(
            @Qualifier("conversationVectorStore") VectorStore conversationVectorStore,
            ConversationMemoryProperties props) {
        this.conversationVectorStore = conversationVectorStore;
        this.props = props;
    }

    // ==================== 写入 ====================

    /**
     * 保存一轮对话
     *
     * @param userId         用户 ID（从 conversationId 提取，不是前端传）
     * @param conversationId 会话 ID（格式 userId:sessionTag）
     * @param turnIndex      轮次序号（由调用方从 Redis INCR 生成）
     * @param userMsg        用户原始消息
     * @param assistantMsg   AI 完整回答
     */
    public void saveTurn(String userId, String conversationId,
                         long turnIndex, String userMsg, String assistantMsg) {
        if (!props.isEnabled()) {
            return;
        }
        if (userMsg == null || userMsg.isBlank()
                || userMsg.length() < props.getMinUserMessageChars()) {
            return;
        }
        if (assistantMsg == null || assistantMsg.isBlank()) {
            return;
        }

        // ★ 落库前脱敏——与 LTM / USER_PREF 保持一致
        String safeUser = SensitiveDataMasker.mask(userMsg);
        String safeAssistant = SensitiveDataMasker.mask(
                truncate(assistantMsg, props.getMaxAssistantChars()));

        try {
            Document doc = new Document(
                    safeUser,   // ★ Document.text = user 消息 → 检索命中率最高
                    Map.of(
                            "user_id", userId,
                            "conversation_id", conversationId,
                            "turn_index", turnIndex,
                            "user_message", safeUser,
                            "assistant_message", safeAssistant,
                            "timestamp", System.currentTimeMillis()
                    )
            );
            conversationVectorStore.add(List.of(doc));
            log.info("?? 对话记忆入库: userId={}, cid={}, turn={}",
                    userId, conversationId, turnIndex);

        } catch (Exception e) {
            // ★ 不向上抛——写入失败不影响主流程（用户问问题成功才是硬道理）
            log.warn("对话记忆入库失败（不影响主流程）: userId={}, cid={}",
                    userId, conversationId, e);
        }
    }

    // ==================== 检索 ====================

    /**
     * 检索与当前 query 语义相关的历史轮次
     *
     * @param userId          用户 ID（强制隔离）
     * @param query           用户当前问题
     * @param excludeConvId   要排除的会话 ID（通常传当前 cid，避免重复注入）
     * @return 相关历史 Document 列表
     */
    public List<Document> search(String userId, String query, String excludeConvId) {
        if (!props.isEnabled() || query == null || query.isBlank()) {
            return List.of();
        }

        try {
            // ★ 强制按 user_id 隔离——这是安全底线
            String filterExpr = "user_id == '" + userId + "'";
            if (props.isExcludeCurrentConversation()
                    && excludeConvId != null && !excludeConvId.isBlank()) {
                filterExpr += " && conversation_id != '" + excludeConvId + "'";
            }

            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(props.getTopK())
                    .similarityThreshold(props.getSimilarityThreshold())
                    .filterExpression(filterExpr)
                    .build();

            List<Document> results = conversationVectorStore.similaritySearch(request);
            if (results == null || results.isEmpty()) {
                log.debug("对话历史未命中: userId={}, query=[{}]",
                        userId, truncate(query, 30));
                return List.of();
            }

            log.info("? 对话历史命中 {} 条: userId={}, query=[{}]",
                    results.size(), userId, truncate(query, 30));
            return results;

        } catch (Exception e) {
            // ★ 降级为空——检索失败不能阻断对话
            log.warn("对话记忆检索失败，降级为空", e);
            return List.of();
        }
    }

    /** 兼容重载——不排除任何会话 */
    public List<Document> search(String userId, String query) {
        return search(userId, query, null);
    }

    // ==================== 辅助 ====================

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}