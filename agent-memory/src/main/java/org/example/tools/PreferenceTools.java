package org.example.tools;

import org.example.preference.UserPreferenceService;
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
            @ToolParam(description = "用户ID") String userId,
            @ToolParam(description = "偏好项，如 city、language、job") String key,
            @ToolParam(description = "偏好值") String value) {
        preferenceService.save(userId, key, value);
        return "已记住：" + key + " = " + value;
    }
}