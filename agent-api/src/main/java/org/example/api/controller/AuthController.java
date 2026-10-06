package org.example.api.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.example.common.audit.AuditLogger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    /** 邀请码 → userId */
    private static final Map<String, String> INVITE_CODES = Map.of(
            "DEMO-001", "user-alice",
            "DEMO-002", "user-bob",
            "DEMO-003", "user-charlie"
    );

    /** userId → tenantId */
    private static final Map<String, String> USER_TENANT = Map.of(
            "user-alice",   "hospital-a",
            "user-bob",     "hospital-a",
            "user-charlie", "hospital-b"
    );

    /** userId → 部门 */
    private static final Map<String, String> USER_DEPT = Map.of(
            "user-alice",   "财务部",
            "user-bob",     "研发部",
            "user-charlie", "财务部"
    );

    /** userId → 密级 */
    private static final Map<String, Integer> USER_SECURITY = Map.of(
            "user-alice",   3,
            "user-bob",     2,
            "user-charlie", 3
    );

    // ==================== 登录 ====================

    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String code, HttpServletRequest request) {
        String userId = INVITE_CODES.get(code);
        if (userId == null) {
            AuditLogger.loginFail(null, "邀请码无效: " + code);
            return Map.of("success", false, "message", "邀请码无效");
        }

        String tenantId = USER_TENANT.getOrDefault(userId, "default");

        HttpSession session = request.getSession(true);
        session.setAttribute("AUTH_USER_ID", userId);
        session.setAttribute("AUTH_TENANT_ID", tenantId);
        session.setAttribute("AUTH_DEPT", USER_DEPT.get(userId));
        session.setAttribute("AUTH_SECURITY_LEVEL",
                USER_SECURITY.getOrDefault(userId, 1));

        // ★ 正确调用——原来传 (null, "登录成功") 是错的
        AuditLogger.loginSuccess(userId, tenantId);

        return Map.of(
                "success", true,
                "userId", userId,
                "tenantId", tenantId
        );
    }

    // ==================== ★ 新增：/me ====================

    /**
     * 获取当前登录用户信息
     * 刷新页面的时候就调用
     * <p>
     * 前端进页面时调——用于判断登录状态、显示用户名/部门。
     * 未登录返回 {@code {loggedIn: false}}——前端据此跳登录页。
     */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("AUTH_USER_ID") == null) {
            return Map.of("loggedIn", false);
        }
        return Map.of(
                "loggedIn", true,
                "userId", session.getAttribute("AUTH_USER_ID"),
                "tenantId", session.getAttribute("AUTH_TENANT_ID"),
                "department", session.getAttribute("AUTH_DEPT"),
                "securityLevel", session.getAttribute("AUTH_SECURITY_LEVEL")
        );
    }

    // ==================== 可选：登出 ====================

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object userId = session.getAttribute("AUTH_USER_ID");
            Object tenantId = session.getAttribute("AUTH_TENANT_ID");
            session.invalidate();
            if (userId != null) {
                AuditLogger.logout(userId.toString(),
                        tenantId == null ? null : tenantId.toString());
            }
        }
        return Map.of("success", true);
    }
}