package org.example.advisor;

import org.example.memory.LongTermMemoryService;
import org.example.common.utils.ConversationIdUtils;
import org.example.common.utils.PromptUtils;
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
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * D19	9/26	周六	记忆检索	基于当前问题检索相关历史记忆片段
 * 长期记忆
 */
public class LongTermMemoryAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryAdvisor.class);
    private static final int TOP_K = 3;

    private final LongTermMemoryService longTermMemoryService;

    public LongTermMemoryAdvisor(LongTermMemoryService longTermMemoryService) {
        this.longTermMemoryService = longTermMemoryService;
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
        log.info("[ENTER] {} order={}", getName(), getOrder());
        return chain.nextStream(enrich(request))
                .doOnComplete(() -> log.info("[EXIT]  {} order={}", getName(), getOrder()));
    }

    private ChatClientRequest enrich(ChatClientRequest request) {
        Object cid = request.context().get(ChatMemory.CONVERSATION_ID);
        if (cid == null) {
            return request;
        }

        String fullUserId = ConversationIdUtils.extractFullUserId(cid.toString());

        // 取最后一条用户消息作为检索 query
        String query = request.prompt().getInstructions().stream()
                .filter(m -> "USER".equals(m.getMessageType().name()))
                .map(m -> m.getText())
                .reduce((a, b) -> b)
                .orElse("");
        if (query.isBlank()) {
            return request;
        }

        List<String> hits = longTermMemoryService.search(fullUserId, query, TOP_K);
        if (hits.isEmpty()) {
            return request;
        }

        String injection = "相关历史记忆（供参考）：\n" + String.join("\n", hits);
        log.info("检索到 {} 条记忆：\n{}", hits.size(), SensitiveDataMasker.mask(injection));
        return PromptUtils.appendSystemMessage(request, injection);
    }

}