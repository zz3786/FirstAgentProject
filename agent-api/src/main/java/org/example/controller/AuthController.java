package org.example.controller;

import jakarta.servlet.http.HttpServletRequest;
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
    public Map<String, Object> login(@RequestParam String code,
                                     HttpServletRequest request) {
        String userId = INVITE_CODES.get(code);
        if (userId == null) {
            return Map.of("success", false, "message", "邀请码无效");
        }

        // 关键：把 userId 写进 Session
        request.getSession(true).setAttribute("AUTH_USER_ID", userId);

        return Map.of("success", true, "userId", userId);
    }
}
