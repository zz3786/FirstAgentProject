package org.example.advisor;

import lombok.extern.slf4j.Slf4j;
import org.example.interest.UserInterestService;
import org.example.utils.PromptUtils;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * D53 用户兴趣标签注入 Advisor
 * <p>
 * <b>执行顺序</b>：order = 120
 * <pre>
 * CompactingChatMemoryAdvisor(50)
 *   → PreferenceAdvisor(100)         ← 结构化偏好（city 等）
 *   → UserInterestAdvisor(120)       ← 兴趣标签（本类）
 *   → RagAdvisor(150)
 *   → MemoryRetrievalAdvisor(200)
 * </pre>
 * <p>
 * <b>读路径</b>——只注入、不写。写入由 InterestTools（模型主动调）完成。
 */
@Slf4j
public class UserInterestAdvisor implements CallAdvisor, StreamAdvisor {

    private final UserInterestService interestService;

    public UserInterestAdvisor(UserInterestService interestService) {
        this.interestService = interestService;
    }

    @Override
    public String getName() {
        return "UserInterestAdvisor";
    }

    @Override
    public int getOrder() {
        return 120;
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
        String userId = extractUserId(cid.toString());

        String text = interestService.renderAsSystemText(userId);
        if (text.isBlank()) {
            return request;
        }

        log.info("D53 注入 {} 条兴趣标签: userId={}", text.lines().count() - 1, userId);
        return PromptUtils.appendSystemMessage(request, text);
    }

    private String extractUserId(String conversationId) {
        int idx = conversationId.indexOf(':');
        return idx > 0 ? conversationId.substring(0, idx) : conversationId;
    }
}