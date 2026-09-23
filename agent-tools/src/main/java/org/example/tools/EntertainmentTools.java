package org.example.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

@Component
public class EntertainmentTools {

    private static final Random RANDOM = new Random();

    private static final List<String> JOKES = List.of(
            "为什么程序员总是分不清万圣节和圣诞节？因为 Oct 31 = Dec 25！",
            "一个 SQL 语句走进酒吧，看到两张桌子，问：我能 JOIN 你们吗？",
            "程序员最讨厌的星座是什么？——Bug 座。",
            "产品经理问：这个功能能不能1天搞定？程序员说：能，但 bug 我留到下辈子修。"
    );

    private static final List<String> QUOTES = List.of(
            "「人生苦短，我用 Python。」—— Tim Peters",
            "「Talk is cheap. Show me the code.」—— Linus Torvalds",
            "「程序必须为人类阅读而编写，顺便让机器执行。」—— Harold Abelson",
            "「过早优化是万恶之源。」—— Donald Knuth",
            "「简单是可靠的先决条件。」—— Edsger Dijkstra"
    );

    @Tool(description = "讲一个程序员冷笑话，用于娱乐、活跃气氛。当用户心情不好或说'来点有意思的'时适用。")
    public String getJoke() {
        return "🤣 " + JOKES.get(RANDOM.nextInt(JOKES.size()));
    }

    @Tool(description = "分享一句编程相关的名言警句，用于励志、深度思考。当用户寻求启发或鼓励时适用。")
    public String getQuote() {
        return "📖 " + QUOTES.get(RANDOM.nextInt(QUOTES.size()));
    }

}
