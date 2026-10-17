package org.example.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.common.audit.AuditLogger;
import org.example.rag.shared.model.RagFilter;
import org.example.core.chat.service.ChatService;
import org.example.common.utils.ConversationIdUtils;
import org.example.api.utils.SessionUtils;
import org.example.api.utils.TenantRequestUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话接口
 * <p>
 * 响应格式策略：
 * - 同步接口 → 用 ApiResponse&lt;T&gt; 包装（统一响应体）
 * - 流式接口 → 保持裸 Flux&lt;String&gt;（SSE 协议要求，不能包装）
 * <p>
 * 所有异常由 GlobalExceptionHandler 统一处理。
 */
@Slf4j
@RestController
@RequestMapping("chat")
@Tag(name = "对话管理", description = "包含同步对话、流式对话、带记忆流式对话等核心接口") // ★ D79 新增
public class ChatController {

    @Resource
    private ChatService chatService;

    /**
     * 同步对话（无记忆、无工具）
     * <p>
     * 返回：ApiResponse 包装的完整回复
     * 示例：{"code":200,"message":"success","data":"你好..."}
     */
    @GetMapping("sync")
    public ApiResponse<String> sync(@RequestParam String message) {
        return ApiResponse.ok(chatService.syncChat(message));
    }

    /**
     * 流式对话（无记忆、无工具）
     * <p>
     * 返回：SSE 流，逐 token 推送
     * 不能用 ApiResponse 包装——那会破坏流式协议
     */
    @GetMapping(value = "stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String message) {
        return chatService.streamChat(message);
    }

    /**
     * 流式对话 + 会话记忆 + 工具调用（主入口）
     * <p>
     * 返回：SSE 流，逐 token 推送
     * 出错时 ChatService 内部用 onErrorResume 转成 "⚠️ ..." 文本，仍走流式
     */
    @GetMapping(value = "streamR", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话 (带记忆与工具)", description = "主入口：支持会话记忆、工具调用、RAG 检索、多租户隔离")
    public Flux<String> streamR(
            @Parameter(description = "提问内容", example = "查一下订单1001") @RequestParam String message,
            @Parameter(description = "会话ID", example = "sess-123") @RequestParam(required = false) String sessionId,
            @Parameter(description = "部门过滤") @RequestParam(required = false) List<String> departments,
            @Parameter(description = "年份起") @RequestParam(required = false) Integer yearFrom,
            @Parameter(description = "文档类型") @RequestParam(required = false) List<String> docTypes,
            @Parameter(description = "是否包含归档") @RequestParam(required = false, defaultValue = "false") Boolean includeArchived,
            // ★ 重点：隐藏 Servlet 对象，不然 Swagger 解析报错
            @Parameter(hidden = true) HttpServletRequest request) {

        // ============ ★ D54：租户 + 完整 userId ============
        String tenantId = TenantRequestUtils.getTenantId(request);
        String fullUserId = TenantRequestUtils.fullUserId(request);   // hospital-a:user-alice

        // ★ conversationId 由工具类组装——内含 sessionTag 校验
        String conversationId = ConversationIdUtils.build(fullUserId, sessionId);

        // ============ 服务端强制项 ============
        int userSecurityLevel = SessionUtils.getSecurityLevel(request);

        List<String> statuses = Boolean.TRUE.equals(includeArchived)
                ? List.of("active", "archived")
                : List.of("active");

        // ============ 前端可控项：部门（越权校验）============
        String userDept = SessionUtils.getDepartment(request);
        List<String> effectiveDepts = new ArrayList<>();

        if (departments != null && !departments.isEmpty()) {
            for (String d : departments) {
                if ("公开".equals(d) || d.equals(userDept)) {
                    effectiveDepts.add(d);
                } else {
                    AuditLogger.unauthorizedDeptFilter(tenantId, fullUserId, d, userDept);
                }
            }
        } else if (userDept != null) {
            effectiveDepts.add(userDept);
        }

        if (!effectiveDepts.contains("公开")) {
            effectiveDepts.add("公开");
        }

        // ============ 组装 filter ============
        RagFilter filter = new RagFilter(
                tenantId,
                effectiveDepts,
                yearFrom, null,
                docTypes, null,
                userSecurityLevel,
                statuses
        );

        log.info("streamR: fullUserId={}, conversationId={}, securityLevel={}, filter={}",
                fullUserId, conversationId, userSecurityLevel, filter);

        return chatService.streamChatWithMemory(message, conversationId, filter);
    }

    @PostMapping("clear")
    @Operation(summary = "清空记忆", description = "彻底清除该会话的短期和长期记忆")
    public ApiResponse<Void> clear(
            @Parameter(description = "会话ID") @RequestParam(required = false) String sessionId,
            @Parameter(hidden = true) HttpServletRequest request) {

        String tenantId = TenantRequestUtils.getTenantId(request);
        String fullUserId = TenantRequestUtils.fullUserId(request);
        String conversationId = ConversationIdUtils.build(fullUserId, sessionId);

        // ★ 审计
        AuditLogger.sensitiveOp(tenantId, fullUserId, "CLEAR_CONVERSATION", conversationId);

        chatService.clearMemory(conversationId);
        return ApiResponse.ok(null);
    }
}