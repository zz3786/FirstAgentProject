package org.example.tools;

import lombok.extern.slf4j.Slf4j;
import org.example.interest.UserInterestService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * D53 兴趣标签工具——让模型能主动记录用户关注的技术领域
 * <p>
 * <b>触发场景</b>：
 * <ul>
 *   <li>"我在学 Spring AI"</li>
 *   <li>"我对 RAG 比较感兴趣"</li>
 *   <li>"以后多给我讲讲 MCP"</li>
 *   <li>"别再推荐前端相关的了"（这个暂不支持——D53 只做正向标签）</li>
 * </ul>
 * <p>
 * <b>为什么不抽取为负向标签</b>：
 * 负向标签在检索/推荐里的语义复杂（是"完全不查"还是"降低排序"），
 * 先只做正向——后面需要再加。
 */
@Slf4j
@Component
public class InterestTools {

    private final UserInterestService interestService;

    public InterestTools(UserInterestService interestService) {
        this.interestService = interestService;
    }

    @Tool(description = "记录用户关注的技术领域或兴趣标签。" +
            "当用户明确表达'我在学 XX'、'我关注 XX'、'我对 XX 感兴趣'时调用。" +
            "不要主动调用——只在用户明确表达时记录。")
    public String recordInterest(
            @ToolParam(description = "技术领域/兴趣标签，如 'Spring AI'、'RAG'、'医疗信息化'")
            String tag,
            ToolContext toolContext) {

        String userId = (String) toolContext.getContext().get("userId");
        if (userId == null || userId.isBlank()) {
            return "⚠️ 系统错误：无法识别用户身份";
        }

        interestService.record(userId, tag);
        return "✅ 已记住您关注的领域：" + tag;
    }
}