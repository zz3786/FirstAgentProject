package org.example.tools;

import org.example.memory.LongTermMemoryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class MemoryTools {

    private final LongTermMemoryService memoryService;

    public MemoryTools(LongTermMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool(description = "记录一条重要的长期记忆（跨会话保留）。" +
            "当用户说'记住'、'以后'、'提醒我'，或提到重要事实（订单号、承诺、个人背景）时调用。")
    public String saveLongTermMemory(
            @ToolParam(description = "用户ID") String userId,
            @ToolParam(description = "类型：FACT / SUMMARY / EVENT") String type,
            @ToolParam(description = "一句话概括的记忆内容") String content) {
        memoryService.save(userId, type, content);
        return "已记录：" + content;
    }
}