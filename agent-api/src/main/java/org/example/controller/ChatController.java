package org.example.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.rag.model.RagFilter;
import org.example.service.ChatService;
import org.example.utils.SessionUtils;
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
    public Flux<String> streamR(
            // 业务参数
            @RequestParam String message,
            // 会话参数
            @RequestParam(required = false) String sessionId,
            // 用户可选筛选维度（前端可传）
            @RequestParam(required = false) List<String> departments,
            @RequestParam(required = false) Integer yearFrom,
            @RequestParam(required = false) List<String> docTypes,
            @RequestParam(required = false, defaultValue = "false") Boolean includeArchived,
            HttpServletRequest request) {

        String userId = SessionUtils.getUserId(request);

        // conversationId = userId:sessionId
        String effectiveSessionId = (sessionId == null || sessionId.isBlank())
                ? "default" : sessionId;
        String conversationId = userId + ":" + effectiveSessionId;

        // ============ 服务端强制项 ============
        // ★ 密级——从 Session 取，前端不可传
        int userSecurityLevel = SessionUtils.getSecurityLevel(request);

        // ★ 状态——默认 active；includeArchived 放开
        List<String> statuses = Boolean.TRUE.equals(includeArchived)
                ? List.of("active", "archived")
                : List.of("active");

        // ============ 前端可控项：部门 ============
        // ★ 用 ArrayList——因为下面要 add("公开")，List.of() / 前端传的 List 可能不可变
        List<String> effectiveDepts = new ArrayList<>();

        if (departments != null && !departments.isEmpty()) {
            // 前端传了——以它为准
            effectiveDepts.addAll(departments);
        } else if (SessionUtils.getDepartment(request) != null) {
            // 前端没传——用 Session 里的部门
            effectiveDepts.add(SessionUtils.getDepartment(request));
        }
        // 若 Session 也没有部门（如未登录兜底场景），effectiveDepts 保持为空

        // ★ 追加"公开"——所有部门都能看到通用文档
        //   去重判断——防止前端已传"公开"导致重复
        if (!effectiveDepts.contains("公开")) {
            effectiveDepts.add("公开");
        }

        // ============ 组装 filter ============
        RagFilter filter = new RagFilter(
                effectiveDepts,
                yearFrom, null,
                docTypes, null,
                userSecurityLevel,     // ★ 强制
                statuses               // ★ 默认策略
        );

        log.info("streamR: userId={}, conversationId={}, securityLevel={}, filter={}",
                userId, conversationId, userSecurityLevel, filter);

        return chatService.streamChatWithMemory(message, conversationId, filter);
    }

    /**
     * 清空当前会话
     * <p>
     * 返回：ApiResponse 包装的成功标记
     */
    @PostMapping("clear")
    public ApiResponse<Void> clear(HttpServletRequest request) {
        String conversationId = SessionUtils.getUserId(request);
        chatService.clearMemory(conversationId);
        return ApiResponse.ok(null);
    }
}