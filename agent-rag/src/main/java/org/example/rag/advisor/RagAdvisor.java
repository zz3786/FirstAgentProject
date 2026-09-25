package org.example.rag.advisor;   // ← 注意包名

import lombok.extern.slf4j.Slf4j;
import org.example.rag.config.RagProperties;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
public class RagAdvisor implements CallAdvisor, StreamAdvisor {

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;   // ← 注入

    public RagAdvisor(VectorStore vectorStore, RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
    }

    @Override
    public String getName() {
        return "RagAdvisor";
    }

    @Override
    public int getOrder() {
        return 150;   // 在 PreferenceAdvisor(100) 之后、MemoryRetrievalAdvisor(200) 之前
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(enrich(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(enrich(request));
    }

    private ChatClientRequest enrich(ChatClientRequest request) {
        // 1. 提取用户问题
        String query = request.prompt().getInstructions().stream()
                .filter(m -> "USER".equals(m.getMessageType().name()))
                .map(Message::getText)
                .reduce((a, b) -> b)
                .orElse("");
        if (query.isBlank()) {
            return request;
        }

        // 2. 向量检索
        List<Document> docs;
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(ragProperties.getTopK());

            if (ragProperties.getSimilarityThreshold() > 0) {
                builder.similarityThreshold(ragProperties.getSimilarityThreshold());
            }

            docs = vectorStore.similaritySearch(builder.build());
        } catch (Exception e) {
            log.error("RAG 检索失败，跳过", e);
            return request;
        }
        if (docs.isEmpty()) {
            return request;
        }

        // 3. 拼装上下文（带来源 + docId + 链接）
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);

            // ↓ 提取 metadata
            String source = (String) doc.getMetadata().getOrDefault("source", "未知文档");
            String docId = (String) doc.getMetadata().get("doc_id");
            Object page = doc.getMetadata().getOrDefault("page_number", "1");

            // ↓ 生成可点击链接
            String link = (docId != null && !docId.isBlank())
                    ? "/fap/rag/file/download/" + docId
                    : "#";

            // ↓ 每条片段：来源 + 页码 + 链接
            ctx.append(String.format(
                    "[%d] 来源：%s（第 %s 页）| 下载链接：%s\n%s\n\n",
                    i + 1, source, page, link, doc.getText()));
        }

        // ↓ 关键：要求模型用 markdown 链接格式
        String injection = """
            参考资料：
            %s
            
            【回答要求】
            1. 只基于上述参考资料回答，不要编造
            2. 如果资料中没有答案，明确说"资料中未提及"
            3. 【必须】在回答末尾用 **markdown 链接格式** 列出引用来源，格式示例：
               📄 来源：[《文档名称》第X页](下载链接)
            4. 链接必须原样复制上面资料中的「下载链接」，不要改写
            5. 引用多条资料时，每条单独一行
            """.formatted(ctx);

        log.info("RAG 注入 {} 条片段", docs.size());

        Prompt newPrompt = request.prompt().augmentSystemMessage(injection);
        return request.mutate().prompt(newPrompt).build();
    }
}