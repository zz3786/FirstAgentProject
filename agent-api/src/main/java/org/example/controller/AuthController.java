package org.example.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    /** 演示用：固定邀请码 → 固定 userId */
    private static final Map<String, String> INVITE_CODES = Map.of(
            "DEMO-001", "user-alice",
            "DEMO-002", "user-bob"
    );

    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String code, HttpServletRequest request) {
        String userId = INVITE_CODES.get(code);
        if (userId == null) {
            return Map.of("success", false, "message", "邀请码无效");
        }

        HttpSession session = request.getSession(true);
        session.setAttribute("AUTH_USER_ID", userId);

        // ★ 演示用：根据 userId 硬编码部门 + 密级
        //   生产环境应从用户表 / 权限系统查询
        switch (userId) {
            case "user-alice" -> {
                session.setAttribute("AUTH_DEPT", "财务部");
                session.setAttribute("AUTH_SECURITY_LEVEL", 3);   // 财务部能看"秘密"
            }
            case "user-bob" -> {
                session.setAttribute("AUTH_DEPT", "研发部");
                session.setAttribute("AUTH_SECURITY_LEVEL", 2);   // 研发部只能看"内部"
            }
            default -> {
                session.setAttribute("AUTH_DEPT", null);
                session.setAttribute("AUTH_SECURITY_LEVEL", 1);   // 默认只能看"公开"
            }
        }

        return Map.of("success", true, "userId", userId);
    }
}
