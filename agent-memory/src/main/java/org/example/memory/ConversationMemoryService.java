package org.example.memory;

import lombok.extern.slf4j.Slf4j;
import org.example.common.utils.RedisTagUtils;
import org.example.common.utils.TextUtils;
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
        String safeAssistant = SensitiveDataMasker.mask(TextUtils.truncate(assistantMsg, props.getMaxAssistantChars()));

        // ★ 关键：user_id 里的 '-' ':' 等是 RediSearch 保留字——统一转义成 '_'
        String safeUserId = RedisTagUtils.escape(userId);

        try {
            Document doc = new Document(
                    safeUser,
                    Map.of(
                            "user_id", safeUserId,                  // ★ 存转义后的
                            "conversation_id", RedisTagUtils.escape(conversationId),
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
            String safeUserId = RedisTagUtils.escape(userId);
            String filterExpr = "user_id == '" + safeUserId + "'";

            if (props.isExcludeCurrentConversation()
                    && excludeConvId != null && !excludeConvId.isBlank()) {
                filterExpr += " && conversation_id != '" + RedisTagUtils.escape(excludeConvId) + "'";
            }

            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(props.getTopK())
                    .similarityThreshold(props.getSimilarityThreshold())
                    .filterExpression(filterExpr)
                    .build();

            List<Document> results = conversationVectorStore.similaritySearch(request);
            if (results == null || results.isEmpty()) {
                log.info("对话历史（向量库）未命中: userId={}, query=[{}]",
                        safeUserId, TextUtils.truncate(query, 30));
                return List.of();
            }

            log.info("✅ 对话历史（）向量库命中 {} 条: userId={}, query=[{}]",
                    results.size(), userId, TextUtils.truncate(query, 30));
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
     * 按 conversationId 删除该会话的所有对话历史
     * <p>
     * <b>用途</b>：用户在 UI 上点"清空对话"时，除了清短期记忆 CHAT，
     * 还要清这个会话在向量库里的所有轮次——彻底遗忘。
     * <p>
     * <b>为什么放这里而不是 ChatService 里直接调 vectorStore</b>：
     * conversationVectorStore 是本类的 private 字段——只有本类能访问。
     * 保持"谁持有谁提供操作"的内聚原则。
     * <p>
     * <b>为什么不用 RedisTagUtils.escape 之外的写法</b>：
     * 存的时候 conversation_id 转过义（saveTurn 里），
     * 查/删必须用同一规则，否则匹配不上——跟 user_id 一样的坑。
     *
     * @param conversationId 完整会话 ID（"hospital-a:user-alice:c383288e"）
     * @return 是否执行成功（异常时返回 false，不向上抛，避免阻断主流程）
     */
    public boolean deleteByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return false;
        }
        try {
            String safeConvId = RedisTagUtils.escape(conversationId);
            conversationVectorStore.delete("conversation_id == '" + safeConvId + "'");
            log.info("🗑️ 已删除对话历史向量: conversationId={}", conversationId);
            return true;
        } catch (Exception e) {
            // ★ 删除失败不该让"清空对话"整体失败——记日志，返回 false
            log.warn("删除对话历史向量失败: conversationId={}", conversationId, e);
            return false;
        }
    }
}