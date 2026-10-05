package org.example.core.plan.config;

import org.example.tools.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * Plan-and-Execute 三个 ChatClient 装配
 * <p>
 * 三者职责分离：
 * <ul>
 *   <li>plannerClient——只生成计划，不带工具、不带记忆</li>
 *   <li>executorClient——执行单个步骤，带工具、不带记忆（状态我们自己管）</li>
 *   <li>synthesizerClient——只做汇总，无工具</li>
 * </ul>
 */
@Configuration
public class PlanChatClientConfig {

    /** 规划器——只做规划，不带任何 Advisor */
    @Bean("plannerClient")
    public ChatClient plannerClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个严谨的任务规划助手。")
                .build();
    }

    /** 执行器——每步独立调用，带工具 */
    @Bean("planExecutorClient")
    public ChatClient planExecutorClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个可靠的执行助手。")
                .build();
    }

    /** 汇总器——无工具，只组织语言 */
    @Bean("planSynthesizerClient")
    public ChatClient planSynthesizerClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个简洁清晰的汇总助手。")
                .build();
    }

    /**
     * Plan 专用工具回调——独立于 ChatService 里那套
     * <p>
     * 独立一套的好处：
     * <ul>
     *   <li>不与 ReAct 路径互相污染</li>
     *   <li>可以只注册 Plan 场景需要的工具（更少的工具 = 更稳的规划）</li>
     * </ul>
     */
    @Bean("planToolCallbacks")
    public ToolCallback[] planToolCallbacks(
            CalculatorTools calculatorTools,
            TextAnalysisTools textAnalysisTools,
            OrderTools orderTools,
            TodoTools todoTools,
            EntertainmentTools entertainmentTools) {

        ToolCallback[] raw = MethodToolCallbackProvider.builder()
                .toolObjects(calculatorTools, textAnalysisTools,
                        orderTools, todoTools, entertainmentTools)
                .build()
                .getToolCallbacks();

        return Arrays.stream(raw).map(SafeToolCallback::new).toArray(ToolCallback[]::new);
    }
}