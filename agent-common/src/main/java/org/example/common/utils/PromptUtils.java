package org.example.common.utils;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 追加工具
 * <p>
 * <b>为什么不用 Spring AI 的 augmentSystemMessage</b>：
 * Spring AI 1.1.8 的 {@code augmentSystemMessage} 内部是
 * {@code systemMessage.mutate().text(...)}——<b>替换</b>已有 SystemMessage，
 * 不是追加。多个 Advisor 都调它，只有最后一个生效。
 * <p>
 * <b>本类作用</b>：真的往 messages 列表 add——
 * 多条 SystemMessage / UserMessage 累积，不互相覆盖。
 * <p>
 * <b>所属模块</b>：agent-common——被 agent-memory / agent-rag / agent-core 共用。
 */
public final class PromptUtils {

    private PromptUtils() {}

    /** 真·追加一条 SystemMessage */
    public static ChatClientRequest appendSystemMessage(ChatClientRequest request, String text) {
        return appendMessage(request, new SystemMessage(text));
    }

    /** 真·追加一条 UserMessage */
    public static ChatClientRequest appendUserMessage(ChatClientRequest request, String text) {
        return appendMessage(request, new UserMessage(text));
    }

    /** 通用追加 */
    public static ChatClientRequest appendMessage(ChatClientRequest request, Message message) {
        List<Message> old = request.prompt().getInstructions();
        List<Message> newList = new ArrayList<>(old);
        newList.add(message);
        Prompt newPrompt = new Prompt(newList, request.prompt().getOptions());
        return request.mutate().prompt(newPrompt).build();
    }
}