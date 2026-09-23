package org.example.advisor;

import org.example.preference.UserPreferenceService;
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

/**
 * 存储用户偏好（如常用城市），下次对话自动注入
 */
public class PreferenceAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PreferenceAdvisor.class);

    private final UserPreferenceService preferenceService;

    public PreferenceAdvisor(UserPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @Override
    public String getName() {
        return "PreferenceAdvisor";
    }

    @Override
    public int getOrder() {
        // 比 MessageChatMemoryAdvisor 大，保证它在记忆处理之后再注入偏好
        return 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(inject(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(inject(request));
    }

    private ChatClientRequest inject(ChatClientRequest request) {
        // 从上下文拿到 conversationId
        Object cid = request.context().get(ChatMemory.CONVERSATION_ID);
        if (cid == null) {
            return request;
        }

        String userId = extractUserId(cid.toString());
        String prefs = preferenceService.renderAsSystemText(userId);
        if (prefs.isBlank()) {
            return request;
        }

        log.info("注入用户偏好 [{}]:\n{}", userId, SensitiveDataMasker.mask(prefs));

        Prompt newPrompt = request.prompt().augmentSystemMessage(prefs);
        return request.mutate().prompt(newPrompt).build();
    }

    /** conversationId 约定格式 "userId:sessionTag"，取 userId 部分 */
    private String extractUserId(String conversationId) {
        int idx = conversationId.indexOf(':');
        return idx > 0 ? conversationId.substring(0, idx) : conversationId;
    }
}