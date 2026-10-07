package org.example.core.plan.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Plan-and-Execute 三个 ChatClient 装配（D68 精简版）
 *
 * <h3>职责</h3>
 * <p>
 * 本类<b>只负责装配三个 ChatClient</b>，不做任何工具装配或工具清单构造。
 *
 * <h3>三个 Client 的定位</h3>
 * <ul>
 *   <li>{@code plannerClient}——生成计划，不带工具、不带记忆</li>
 *   <li>{@code planExecutorClient}——执行单个步骤，工具<b>由调用方</b>
 *       （{@link org.example.core.plan.executor.StepExecutor}）动态传入</li>
 *   <li>{@code planSynthesizerClient}——结果汇总，无工具</li>
 * </ul>
 *
 * <h3>D68 的改动</h3>
 * <p>
 * 本类原有一个 {@code planToolCallbacks} Bean——它手写了 5 个工具类，
 * 与 yml 白名单、{@code ToolRegistry} 三方独立维护，极易不一致。
 *
 * <p>D68 已把"取工具"的职责下移到两个消费者：
 * <ul>
 *   <li>{@link org.example.core.plan.planner.PlannerService}——
 *       生成 Plan 时从 {@code ToolRegistry} 动态取</li>
 *   <li>{@link org.example.core.plan.executor.StepExecutor}——
 *       执行单步时从 {@code ToolRegistry} 动态取</li>
 * </ul>
 *
 * <p>因此本类的 {@code planToolCallbacks} Bean 已删除。
 */
@Configuration
public class PlanChatClientConfig {

    /** 规划器——只做规划，不带任何 Advisor、不带工具 */
    @Bean("plannerClient")
    public ChatClient plannerClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个严谨的任务规划助手。")
                .build();
    }

    /** 执行器——每步独立调用，工具由 StepExecutor 动态传入 */
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
}