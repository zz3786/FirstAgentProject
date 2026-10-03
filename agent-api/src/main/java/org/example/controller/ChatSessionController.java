package org.example.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.example.api.common.ApiResponse;
import org.example.common.audit.AuditLogger;
import org.example.repository.RedisChatMemoryRepository;
import org.example.utils.SessionUtils;
import org.example.utils.TenantRequestUtils;
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

    /**
     * 列出当前用户的会话 ID
     */
    @GetMapping
    public ApiResponse<List<String>> list(HttpServletRequest request) {
        String tenantId = TenantRequestUtils.getTenantId(request);
        String rawUserId = SessionUtils.getUserId(request);

        List<String> sessions = redisChatMemoryRepository
                .findConversationIdsByUser(tenantId, rawUserId);

        return ApiResponse.ok(sessions);
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
            redisChatMemoryRepository.deleteByConversationId(cid);
        }

        AuditLogger.sensitiveOp(tenantId, tenantId + ":" + rawUserId,
                "CLEAR_ALL_SESSIONS", "count=" + sessions.size());

        return ApiResponse.ok(sessions.size());
    }
}
