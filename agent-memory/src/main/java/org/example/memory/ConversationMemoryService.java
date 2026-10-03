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

        String safeUser = SensitiveDataMasker.mask(userMsg);
        String safeAssistant = SensitiveDataMasker.mask(
                truncate(assistantMsg, props.getMaxAssistantChars()));

        // ★ 关键：user_id 里的 '-' ':' 等是 RediSearch 保留字——统一转义成 '_'
        String safeUserId = escapeRedisTag(userId);

        try {
            Document doc = new Document(
                    safeUser,
                    Map.of(
                            "user_id", safeUserId,                  // ★ 存转义后的
                            "conversation_id", conversationId,
                            "turn_index", turnIndex,
                            "user_message", safeUser,
                            "assistant_message", safeAssistant,
                            "timestamp", System.currentTimeMillis()
                    )
            );
            conversationVectorStore.add(List.of(doc));
            log.info("📥 对话记忆入库: userId={}, cid={}, turn={}",
                    userId, conversationId, turnIndex);

        } catch (Exception e) {
            log.warn("对话记忆入库失败（不影响主流程）: userId={}, cid={}",
                    userId, conversationId, e);
        }
    }

    // ==================== 检索 ====================

    public List<Document> search(String userId, String query, String excludeConvId) {
        if (!props.isEnabled() || query == null || query.isBlank()) {
            return List.of();
        }

        try {
            // ★ 关键：查询时同样转义——和存储保持一致的规则
            String safeUserId = escapeRedisTag(userId);
            String filterExpr = "user_id == '" + safeUserId + "'";

            if (props.isExcludeCurrentConversation()
                    && excludeConvId != null && !excludeConvId.isBlank()) {
                filterExpr += " && conversation_id != '" + escapeRedisTag(excludeConvId) + "'";
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

            log.info("✅ 对话历史命中 {} 条: userId={}, query=[{}]",
                    results.size(), userId, truncate(query, 30));
            return results;

        } catch (Exception e) {
            log.warn("对话记忆检索失败，降级为空", e);
            return List.of();
        }
    }

    public List<Document> search(String userId, String query) {
        return search(userId, query, null);
    }

    // ==================== 辅助 ====================

    /**
     * RediSearch TAG 字段转义
     * <p>
     * RediSearch 里 TAG 类型的值——{@code - : , . | 空格} 等
     * 都是保留字符，不转义会报 "Syntax error"。
     * <p>
     * 统一策略：非字母数字下划线 → 下划线。
     * 存和查用同一规则——保证匹配一致。
     */
    private String escapeRedisTag(String s) {
        if (s == null) return "default";
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}