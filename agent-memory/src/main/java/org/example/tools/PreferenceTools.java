package org.example.tools;

import org.example.preference.UserPreferenceService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class PreferenceTools {

    private final UserPreferenceService preferenceService;

    public PreferenceTools(UserPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @Tool(description = "记住用户的偏好信息（如常用城市、语言、职业等）。当用户明确表达偏好时调用。")
    public String savePreference(
            @ToolParam(description = "偏好项，如 city、language、job") String key,
            @ToolParam(description = "偏好值") String value,
            ToolContext toolContext) {

        String userId = (String) toolContext.getContext().get("userId");
        if (userId == null || userId.isBlank()) {
            return "⚠️ 系统错误：无法识别用户身份";
        }

        preferenceService.save(userId, key, value);
        return "已记住：" + key + " = " + value;
    }
}