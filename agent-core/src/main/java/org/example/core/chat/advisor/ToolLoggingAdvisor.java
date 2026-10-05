package org.example.core.chat.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

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
        // 最内层：请求日志看到所有 Advisor 注入后的最终 prompt
        //          响应日志看到模型原始 tool_call / 文本
        return Ordered.LOWEST_PRECEDENCE;
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
        log.info("[ENTER] {} order={} —— 即将调用模型", getName(), getOrder());
        log.error("★★★ ToolLoggingAdvisor 执行了 order={}", getOrder());
        logRequest(request);
        StringBuilder fullText = new StringBuilder();
        return chain.nextStream(request)
                .doOnNext(response -> accumulate(response, fullText))
                .doOnComplete(() -> {
                    log.info("[EXIT]  {} order={} —— 模型原始输出完毕", getName(), getOrder());
                    if (!fullText.isEmpty()) {
                        log.info("========== [流式响应内容] ==========\n{}", fullText);
                    }
                })
                .doOnError(err -> log.error("[ERROR] {} : {}", getName(), err.getMessage()));
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