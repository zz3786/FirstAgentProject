package org.example.advisor;

import org.example.memory.LongTermMemoryService;
import org.example.utils.SensitiveDataMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * D19	9/26	周六	记忆检索	基于当前问题检索相关历史记忆片段
 * 长期记忆
 */
public class MemoryRetrievalAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(MemoryRetrievalAdvisor.class);
    private static final int TOP_K = 3;

    private final LongTermMemoryService memoryService;

    public MemoryRetrievalAdvisor(LongTermMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String getName() {
        return "MemoryRetrievalAdvisor";
    }

    @Override
    public int getOrder() {
        return 200;   // 在 PreferenceAdvisor(100) 之后
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
        Object cid = request.context().get(ChatMemory.CONVERSATION_ID);
        if (cid == null) {
            return request;
        }

        String userId = extractUserId(cid.toString());

        // 取最后一条用户消息作为检索 query
        String query = request.prompt().getInstructions().stream()
                .filter(m -> "USER".equals(m.getMessageType().name()))
                .map(m -> m.getText())
                .reduce((a, b) -> b)
                .orElse("");
        if (query.isBlank()) {
            return request;
        }

        List<String> hits = memoryService.search(userId, query, TOP_K);
        if (hits.isEmpty()) {
            return request;
        }

        String injection = "相关历史记忆（供参考）：\n" + String.join("\n", hits);

        log.info("检索到 {} 条记忆：\n{}", hits.size(), SensitiveDataMasker.mask(injection));

        Prompt newPrompt = request.prompt().augmentSystemMessage(injection);
        return request.mutate().prompt(newPrompt).build();
    }

    private String extractUserId(String conversationId) {
        int idx = conversationId.indexOf(':');
        return idx > 0 ? conversationId.substring(0, idx) : conversationId;
    }
}