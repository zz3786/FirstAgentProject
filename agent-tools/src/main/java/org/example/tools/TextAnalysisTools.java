package org.example.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class TextAnalysisTools {

    @Tool(description = "分析一段文本，统计总字数（含标点）和指定词的出现次数")
    public String analyzeText(
            @ToolParam(description = "待分析的完整原始文本", required = true) String text,
            @ToolParam(description = "要统计出现次数的关键词，不提供则只统计字数", required = false)
            String keyword) {

        int charCount = text.length();
        int wordCount = text.split("\\s+").length;

        if (keyword == null || keyword.isEmpty()) {
            return String.format("文本总字数（含标点）：%d，总词数：%d", charCount, wordCount);
        }

        int keywordCount = 0;
        String lowerText = text.toLowerCase();
        String lowerKeyword = keyword.toLowerCase();
        int index = lowerText.indexOf(lowerKeyword);
        while (index != -1) {
            keywordCount++;
            index = lowerText.indexOf(lowerKeyword, index + lowerKeyword.length());
        }

        return String.format("文本总字数（含标点）：%d，总词数：%d，关键词「%s」出现 %d 次",
                charCount, wordCount, keyword, keywordCount);
    }

}
