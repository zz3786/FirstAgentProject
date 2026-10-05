package org.example.utils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionUtils {

    private static final Logger log = LoggerFactory.getLogger(SessionUtils.class);
    private static final String DEFAULT_SESSION_HEADER_NAME = "X-Session-Id";
    private static final String DEFAULT_SESSION_COOKIE_NAME = "SESSION";

    // ==================== 已有方法（保持不变） ====================

    public static String getUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object userId = session.getAttribute("AUTH_USER_ID");
            if (userId != null) {
                return userId.toString();
            }
        }
        throw new IllegalStateException("未登录");
    }

    public static String getConversationId(HttpServletRequest request) {
        String userId = getUserId(request);
        String sessionTag = getSessionTag(request);
        String conversationId = userId + ":" + sessionTag;

        log.debug("conversationId = {}", conversationId);
        return conversationId;
    }

    private static String getSessionTag(HttpServletRequest request) {
        String headerTag = request.getHeader(DEFAULT_SESSION_HEADER_NAME);
        if (headerTag != null && !headerTag.isBlank()) {
            return headerTag;
        }

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (DEFAULT_SESSION_COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        return request.getSession(true).getId();
    }

    // ==================== ★ D46 新增两个方法 ====================

    /**
     * 取当前用户部门
     * <p>
     * 未登录或无部门时返回 null——由调用方决定降级策略。
     */
    public static String getDepartment(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object dept = session.getAttribute("AUTH_DEPT");
        return dept == null ? null : dept.toString();
    }

    /**
     * 取当前用户密级
     * <p>
     * 未登录或无密级时返回 1（最低密级）——安全兜底。
     */
    public static int getSecurityLevel(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return 1;
        }
        Object level = session.getAttribute("AUTH_SECURITY_LEVEL");
        if (level instanceof Number n) {
            return n.intValue();
        }
        if (level != null) {
            try {
                return Integer.parseInt(level.toString());
            } catch (NumberFormatException ignore) {
                // fallthrough
            }
        }
        return 1;
    }
}