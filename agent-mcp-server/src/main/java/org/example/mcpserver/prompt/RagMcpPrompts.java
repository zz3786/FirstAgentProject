package org.example.mcpserver.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

/**
 * MCP 提示模板：RAG 回答（D65）
 *
 * <h3>Prompt 的定位</h3>
 * <p>
 * Tools 是模型驱动、Resources 是应用驱动、Prompts 是用户驱动。
 * 用户在前端点击“用 RAG 模式回答”时，前端通过 MCP 协议
 * 请求这个 Prompt，拿到模板文本 + 参数占位符，再发给模型。
 *
 * <h3>和 RagAdvisor 的关系</h3>
 * <p>
 * {@code agent-rag} 里的 {@code RagAdvisor.buildRagPrompt()} 是
 * “自动注入”——不管用户想不想，RAG 检索到资料就注入。
 * 本类提供的是“主动触发”能力——用户可以选择是否使用 RAG 模式。
 *
 * <p>两者共享同一套 Prompt 工程原则，但触发时机不同。
 * D65 先做最简版本，D66 会把 RagAdvisor 的完整模板搬过来。
 */
@Slf4j
@Component
public class RagMcpPrompts {

    /**
     * MCP 提示：基于知识库回答问题
     *
     * <p>Client 请求：
     * <pre>
     *   prompts/get
     *   { "name": "rag-answer", "arguments": { "question": "家庭医生签约..." } }
     * </pre>
     *
     * <p>Server 返回渲染后的 Prompt 文本。
     *
     * @param question 用户问题
     * @return 渲染后的 Prompt 文本
     */
    @McpPrompt(
            name = "rag-answer",
            description = "基于知识库资料回答用户问题的提示模板"
    )
    public String ragAnswerPrompt(
            @McpArg(description = "用户问题", required = true) String question) {

        log.info("[MCP-Prompt] rag-answer 被请求: question={}", question);

        return """
                你是一个严谨的知识库助手。你的任务是严格基于参考资料回答用户问题。

                回答规则：
                1. 只使用参考资料中的信息回答，禁止用训练知识补充
                2. 资料中没有答案时，直接回答："根据现有资料，无法回答该问题。"
                3. 每条引用的信息后标注来源编号，格式：[资料 1]
                4. 用简洁中文回答，不超过 300 字

                用户问题：%s

                请等待系统注入参考资料后开始回答。
                """.formatted(question);
    }
}