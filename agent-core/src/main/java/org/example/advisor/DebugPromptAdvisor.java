package org.example.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * 调试用 Advisor——打印最终发给模型的完整 messages
 * <p>
 * <b>位置</b>：order = LOWEST_PRECEDENCE - 1——在所有业务 Advisor 之后、
 * ToolLoggingAdvisor 之前——看到的是"注入完毕的最终态"。
 */
@Slf4j
@Profile("dev")
public class DebugPromptAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public String getName() {
        return "DebugPromptAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        dump(request);
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        dump(request);
        return chain.nextStream(request);
    }

    private void dump(ChatClientRequest request) {
        var messages = request.prompt().getInstructions();
        StringBuilder sb = new StringBuilder("\n");
        sb.append("════════════════════════════════════════\n");
        sb.append(" 最终发给模型的消息（共 ").append(messages.size()).append(" 条）\n");
        sb.append("════════════════════════════════════════\n");

        for (int i = 0; i < messages.size(); i++) {
            var m = messages.get(i);
            String text = m.getText();
            if (text != null && text.length() > 500) {
                text = text.substring(0, 500) + "...(截断，完整 " + m.getText().length() + " 字)";
            }
            sb.append(String.format("[%d] %-8s | %s%n", i, m.getMessageType(), text));
        }
        sb.append("════════════════════════════════════════\n");

        log.error(sb.toString());   // 用 error 保证能看到
    }
}