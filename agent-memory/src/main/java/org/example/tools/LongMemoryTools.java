package org.example.tools;

import lombok.extern.slf4j.Slf4j;
import org.example.memory.LongTermMemoryService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LongMemoryTools {

    private final LongTermMemoryService memoryService;

    public LongMemoryTools(LongTermMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool(description = "记录一条重要的长期记忆（跨会话保留）。" +
            "当用户说'记住'、'以后'、'提醒我'，或提到重要事实（订单号、承诺、个人背景）时调用。")
    public String saveLongTermMemory(
            @ToolParam(description = "类型：FACT / SUMMARY / EVENT") String type,
            @ToolParam(description = "一句话概括的记忆内容") String content,
            ToolContext toolContext) {                    // ★ 新增——LLM 看不见

        // ★ userId 从 ToolContext 取，不由 LLM 填
        String userId = (String) toolContext.getContext().get("userId");
        if (userId == null || userId.isBlank()) {
            log.warn("ToolContext 里没有 userId——无法保存长期记忆");
            return "⚠️ 系统错误：无法识别用户身份";
        }
        memoryService.save(userId, type, content);
        return "已记录：" + content;
    }
}