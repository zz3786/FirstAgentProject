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

    /**
     * 生成 conversationId = userId:sessionTag
     * - userId 来自服务端（安全）
     * - sessionTag 客户端可传（无所谓，拼在 userId 后面）
     */
    public static String getConversationId(HttpServletRequest request) {
        String userId = getUserId(request);
        String sessionTag = getSessionTag(request);
        String conversationId = userId + ":" + sessionTag;

        log.debug("conversationId = {}", conversationId);
        return conversationId;
    }

    /**
     * 获取 sessionTag：客户端可传，取不到就生成一个
     */
    private static String getSessionTag(HttpServletRequest request) {
        // 1. 请求头
        String headerTag = request.getHeader(DEFAULT_SESSION_HEADER_NAME);
        if (headerTag != null && !headerTag.isBlank()) {
            return headerTag;
        }

        // 2. Cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (DEFAULT_SESSION_COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        // 3. 兜底：用 HttpSession.getId() 当 tag
        return request.getSession(true).getId();
    }
}