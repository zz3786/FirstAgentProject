package org.example.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.example.api.common.ApiResponse;
import org.example.service.ChatService;
import org.example.utils.SessionUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 对话接口
 * <p>
 * 响应格式策略：
 * - 同步接口 → 用 ApiResponse&lt;T&gt; 包装（统一响应体）
 * - 流式接口 → 保持裸 Flux&lt;String&gt;（SSE 协议要求，不能包装）
 * <p>
 * 所有异常由 GlobalExceptionHandler 统一处理。
 */
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
    public Flux<String> streamR(@RequestParam String message, HttpServletRequest request) {
        String conversationId = SessionUtils.getUserId(request);
        return chatService.streamChatWithMemory(message, conversationId);
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