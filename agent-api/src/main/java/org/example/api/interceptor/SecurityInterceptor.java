package org.example.api.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.common.audit.AuditLogger;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 全局安全拦截器（D81）
 * <p>
 * 负责两件事：
 * 1. Prompt 注入防御：检测恶意关键词，拦截返回 400。
 * 2. 管理接口鉴权：保护 /config/** 和 /mcp/tools/** 等敏感端点。
 */
@Slf4j
@Component
public class SecurityInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 恶意 Prompt 注入特征词 */
    private static final List<String> MALICIOUS_PATTERNS = List.of(
            "忽略之前的指令", "忽略以上", "忽略所有",
            "ignore previous", "ignore all instructions",
            "system prompt", "系统提示词", "你的设定是什么",
            "repeat the words above", "打印你的环境变量", "打印你的配置"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // ==================== 1. 管理接口鉴权 ====================
        // 保护 /config/refresh 和 /mcp/tools/refresh
        if (uri.contains("/config/refresh") || uri.contains("/mcp/tools/refresh")) {
            HttpSession session = request.getSession(false);
            // 假设只有特定用户（比如 admin）才能调用
            if (session == null || !"admin".equals(session.getAttribute("AUTH_USER_ID"))) {
                log.warn("[Security] 拦截未授权访问: uri={}, ip={}", uri, request.getRemoteAddr());
                AuditLogger.event("WARN", "SECURITY_DENY", "uri", uri, "reason", "非管理员访问");
                writeErrorResponse(response, 403, "无权访问该管理接口");
                return false;
            }
        }

        // ==================== 2. Prompt 注入防御 ====================
        // 只拦截对话接口
        if (uri.contains("/chat/")) {
            String message = request.getParameter("message");
            if (message != null) {
                String lowerMsg = message.toLowerCase();
                for (String pattern : MALICIOUS_PATTERNS) {
                    if (lowerMsg.contains(pattern)) {
                        log.warn("[Security] 拦截恶意 Prompt: userMsg={}, ip={}", message, request.getRemoteAddr());
                        AuditLogger.event("WARN", "PROMPT_INJECTION_DENY", "msg", message);

                        // 拦截，返回友好的警告，而不是 500 报错
                        writeErrorResponse(response, 400, "⚠️ 检测到违规输入，已被系统拦截。请遵守使用规范。");
                        return false; // 阻断请求，不再往下走
                    }
                }
            }
        }

        return true;
    }

    /**
     * 将错误信息直接写回给前端
     */
    private void writeErrorResponse(HttpServletResponse response, int code, String msg) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(code);
        ApiResponse<Void> apiResponse = ApiResponse.fail(code, msg);
        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }
}