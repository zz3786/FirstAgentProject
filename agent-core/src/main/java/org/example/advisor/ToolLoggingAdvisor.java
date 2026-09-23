package org.example.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;

public class ToolLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ToolLoggingAdvisor.class);

    /** 是否打印工具 Schema（生产环境可关闭，避免刷屏） */
    private final boolean printToolSchema;

    public ToolLoggingAdvisor() {
        this(true);
    }

    public ToolLoggingAdvisor(boolean printToolSchema) {
        this.printToolSchema = printToolSchema;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        // 越小越先执行。用 HIGHEST_PRECEDENCE + 100 保证比较靠前，但还留出空间
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    // ==================== 同步 ====================
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        logRequest(request);
        try {
            ChatClientResponse response = chain.nextCall(request);
            logResponse(response);
            return response;
        } catch (Exception e) {
            log.error("========== [同步调用异常] ==========", e);
            throw e;
        }
    }

    // ==================== 流式 ====================
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        logRequest(request);
        StringBuilder fullText = new StringBuilder();
        return chain.nextStream(request)
                .doOnNext(response -> accumulate(response, fullText))
                .doOnComplete(() -> {
                    if (!fullText.isEmpty()) {
                        log.info("========== [流式响应完成] ==========\n{}", fullText);
                    }
                })
                .doOnError(err ->
                        log.error("========== [流式响应异常] ==========", err));
    }

    // ==================== 请求日志 ====================
    private void logRequest(ChatClientRequest request) {
        log.info("========== [请求] ==========");
        request.prompt().getInstructions().forEach(msg ->
                log.info("[{}] {}", msg.getMessageType(), msg.getText()));

        if (!printToolSchema) {
            return;
        }

//        ChatOptions options = request.prompt().getOptions();
//        if (options instanceof ToolCallingChatOptions toolCallingOptions) {
//            List<ToolCallback> toolCallbacks = toolCallingOptions.getToolCallbacks();
//            if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
//                log.info("---------- 已注册工具 ({} 个) ----------", toolCallbacks.size());
//                toolCallbacks.forEach(tool -> {
//                    var def = tool.getToolDefinition();
//                    log.info("🔧 工具: {}\n   描述: {}\n   参数: {}",
//                            def.name(), def.description(), def.inputSchema());
//                });
//            }
//        }
    }

    // ==================== 响应日志（同步用） ====================
    private void logResponse(ChatClientResponse response) {
        if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
            return;
        }
        var output = response.chatResponse().getResult().getOutput();

        if (output.getToolCalls() != null && !output.getToolCalls().isEmpty()) {
            log.info("========== [模型返回 tool_call] ==========");
            output.getToolCalls().forEach(tc ->
                    log.info("🎯 工具名: {}\n   参数: {}", tc.name(), tc.arguments()));
        } else if (output.getText() != null && !output.getText().isBlank()) {
            log.info("========== [模型返回文本] ==========\n{}", output.getText());
        }
    }

    // ==================== 流式 token 累积 ====================
    private void accumulate(ChatClientResponse response, StringBuilder buffer) {
        if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
            return;
        }
        var output = response.chatResponse().getResult().getOutput();

        // 累积文本
        if (output.getText() != null) {
            buffer.append(output.getText());
        }

        // 流式下 tool_call 一般不会出现在这里，但保底打印一次
        if (output.getToolCalls() != null && !output.getToolCalls().isEmpty()) {
            output.getToolCalls().forEach(tc ->
                    log.info("🎯 流式 tool_call: {} | 参数: {}", tc.name(), tc.arguments()));
        }
    }
}