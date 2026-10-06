package org.example.api.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.example.api.common.ApiResponse;
import org.example.common.audit.AuditLogger;
import org.example.common.utils.ConversationIdUtils;
import org.example.memory.ConversationMemoryService;
import org.example.repository.RedisChatMemoryRepository;
import org.example.api.utils.SessionUtils;
import org.example.api.utils.TenantRequestUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/chat/sessions")
public class ChatSessionController {

    @Resource
    private RedisChatMemoryRepository redisChatMemoryRepository;

    @Resource
    private ConversationMemoryService conversationMemoryService;

    /**
     * 列出当前用户的会话 ID
     * <p>
     * 返回的是 sessionTag 列表（如 ["c383288e", "539dd0aa"]），
     * <b>不是</b>完整 conversationId——与 /chat/streamR 的入参格式对齐。
     */
    @GetMapping
    public ApiResponse<List<String>> list(HttpServletRequest request) {
        String tenantId = TenantRequestUtils.getTenantId(request);
        String rawUserId = SessionUtils.getUserId(request);

        List<String> conversationIds = redisChatMemoryRepository.findConversationIdsByUser(tenantId, rawUserId);

        // ★ 唯一新增：完整 conversationId → 只保留 sessionTag
        //   "hospital-a:user-alice:c383288e" → "c383288e"
        List<String> sessionTags = conversationIds.stream()
                .map(ConversationIdUtils::extractSessionTag)
                .distinct()
                .toList();

        return ApiResponse.ok(sessionTags);
    }

    /**
     * 清空当前用户所有会话（危险操作——加审计）
     */
    @DeleteMapping
    public ApiResponse<Integer> clearAll(HttpServletRequest request) {
        String tenantId = TenantRequestUtils.getTenantId(request);
        String rawUserId = SessionUtils.getUserId(request);

        List<String> sessions = redisChatMemoryRepository
                .findConversationIdsByUser(tenantId, rawUserId);

        for (String cid : sessions) {
            //清除CHAT
            redisChatMemoryRepository.deleteByConversationId(cid);
            //清除conv-mem
            conversationMemoryService.deleteByConversationId(cid);
        }

        AuditLogger.sensitiveOp(tenantId, tenantId + ":" + rawUserId,
                "CLEAR_ALL_SESSIONS", "count=" + sessions.size());

        return ApiResponse.ok(sessions.size());
    }
}
