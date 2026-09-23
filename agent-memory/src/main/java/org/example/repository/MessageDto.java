package org.example.repository;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageDto {

    private String type;                          // USER / ASSISTANT / SYSTEM / TOOL
    private String textContent;
    private Map<String, Object> metadata;

    // ASSISTANT 专用：工具调用列表
    private List<ToolCallDto> toolCalls;

    // TOOL 专用：工具执行结果
    private List<ToolResponseDto> responses;

    // 空构造，Jackson 用
    public MessageDto() {}

    // ==================== 序列化 ====================

    public static MessageDto from(Message msg) {
        MessageDto dto = new MessageDto();
        dto.type = msg.getMessageType().name();
        dto.textContent = msg.getText();
        dto.metadata = msg.getMetadata();

        // AssistantMessage → 提取 toolCalls
        if (msg instanceof AssistantMessage assistant) {
            dto.toolCalls = assistant.getToolCalls().stream()
                    .map(ToolCallDto::from)
                    .collect(Collectors.toList());
        }

        // ToolResponseMessage → 提取 responses
        if (msg instanceof ToolResponseMessage toolResp) {
            dto.responses = toolResp.getResponses().stream()
                    .map(ToolResponseDto::from)
                    .collect(Collectors.toList());
        }

        return dto;
    }

    // ==================== 反序列化 ====================

    public Message toMessage() {
        return switch (type) {
            case "USER" -> new UserMessage(textContent);
            case "SYSTEM" -> new SystemMessage(textContent);
            case "ASSISTANT" -> {
                List<AssistantMessage.ToolCall> calls = (toolCalls == null || toolCalls.isEmpty())
                        ? List.of()
                        : toolCalls.stream().map(ToolCallDto::toToolCall).toList();

                yield new AssistantMessage(
                        textContent,
                        Map.of(),
                        calls,
                        List.of()
                ) {};
            }
            case "TOOL" -> {
                List<ToolResponseMessage.ToolResponse> resp = (responses == null || responses.isEmpty())
                        ? List.of()
                        : responses.stream().map(ToolResponseDto::toToolResponse).toList();
                yield ToolResponseMessage.builder()
                        .responses(resp)
                        .build();
            }
            default -> throw new IllegalArgumentException("不支持的消息类型: " + type);
        };
    }

    // ==================== 外层 getter / setter ====================

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTextContent() { return textContent; }
    public void setTextContent(String textContent) { this.textContent = textContent; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public List<ToolCallDto> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallDto> toolCalls) { this.toolCalls = toolCalls; }

    public List<ToolResponseDto> getResponses() { return responses; }
    public void setResponses(List<ToolResponseDto> responses) { this.responses = responses; }

    // ==================== ToolCallDto ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCallDto {

        private String id;
        private String type;
        private String name;
        private String arguments;

        public ToolCallDto() {}

        public static ToolCallDto from(AssistantMessage.ToolCall tc) {
            ToolCallDto dto = new ToolCallDto();
            dto.id = tc.id();
            dto.type = tc.type();
            dto.name = tc.name();
            dto.arguments = tc.arguments();
            return dto;
        }

        public AssistantMessage.ToolCall toToolCall() {
            return new AssistantMessage.ToolCall(id, type, name, arguments);
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getArguments() { return arguments; }
        public void setArguments(String arguments) { this.arguments = arguments; }
    }

    // ==================== ToolResponseDto ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolResponseDto {

        private String id;
        private String name;
        private String responseData;

        public ToolResponseDto() {}

        public static ToolResponseDto from(ToolResponseMessage.ToolResponse tr) {
            ToolResponseDto dto = new ToolResponseDto();
            dto.id = tr.id();
            dto.name = tr.name();
            dto.responseData = tr.responseData();
            return dto;
        }

        public ToolResponseMessage.ToolResponse toToolResponse() {
            return new ToolResponseMessage.ToolResponse(id, name, responseData);
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getResponseData() { return responseData; }
        public void setResponseData(String responseData) { this.responseData = responseData; }
    }
}