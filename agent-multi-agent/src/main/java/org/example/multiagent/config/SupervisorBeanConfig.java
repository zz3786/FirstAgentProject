package org.example.multiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 主管 Agent 的 ChatClient 装配（D72）
 *
 * <h3>装配的 3 个 Client</h3>
 * <pre>
 *   OpenAiChatModel
 *       │
 *       ├──→ supervisorPlannerClient      拆解任务（无 Advisor，纯净）
 *       │
 *       ├──→ supervisorSynthesizerClient  汇总答案（无 Advisor，纯净）
 *       │
 *       └──→ reflectiveChatClient         D71 已有的，用作降级路径
 * </pre>
 *
 * <h3>为什么拆解和汇总用不同的 Client</h3>
 * <p>
 * 虽然它们的配置看起来一样（都是"无 Advisor 的裸 Client"），
 * 但 <b>System Prompt 不同</b>：
 * <ul>
 *   <li>planner 的 System Prompt 强调"任务规划师"身份</li>
 *   <li>synthesizer 的 System Prompt 强调"整合专家意见"身份</li>
 * </ul>
 * <p>
 * System Prompt 影响 LLM 的"角色认知"——拆解时更结构化，
 * 汇总时更连贯。这是实践中的经验——比用同一个 Client 效果好。
 *
 * <h3>为什么都不带 SelfReflectionAdvisor</h3>
 * <p>
 * 拆解和汇总都是"内部流程"——它们的输出不是给用户看的最终答案。
 * 反思机制是给"用户能看到的答案"用的。用在这里会：
 * <ul>
 *   <li>增加 2 倍 LLM 调用（每次拆解/汇总都要评估）</li>
 *   <li>拖慢整体响应</li>
 *   <li>可能破坏结构（反思修改 JSON 会破坏解析）</li>
 * </ul>
 */
@Slf4j
@Configuration
public class SupervisorBeanConfig {

    /**
     * 拆解器专用 Client
     */
    @Bean("supervisorPlannerClient")
    public ChatClient supervisorPlannerClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一位严谨的多智能体任务规划师。")
                .build();
    }

    /**
     * 汇总器专用 Client
     */
    @Bean("supervisorSynthesizerClient")
    public ChatClient supervisorSynthesizerClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一位善于整合多方意见的主管。")
                .build();
    }

    /**
     * Worker 基础 Client（D72 新增）
     *
     * <h3>为什么 Worker 不该带 SelfReflectionAdvisor</h3>
     * <p>
     * Worker 的输出是"<b>中间产物</b>"——会被 SupervisorAgent 的汇总
     * LLM 再处理一次。在 Worker 层反思会导致：
     * <ul>
     *   <li><b>延迟翻倍</b>——一次子任务调用变成 2 次 LLM 往返（生成 + 评估）</li>
     *   <li><b>成本翻倍</b>——每次子任务多花一倍 Token</li>
     *   <li><b>收益接近零</b>——反思的答案还要经过汇总环节，重复把关</li>
     * </ul>
     * <p>
     * 反思真正应该放的位置是"<b>用户能直接看到的最终答案</b>"——
     * 即 SupervisorAgent 汇总后。D75 会把这个作为聚合策略的一部分。
     *
     * <h3>实际效果对比（实测）</h3>
     * <pre>
     *   带反思（reflectiveChatClient）：单子任务 ~57 秒
     *   无反思（workerBaseClient）：    单子任务 ~28 秒
     * </pre>
     */
    @Bean("workerBaseClient")
    public ChatClient workerBaseClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一位可靠的专业助手。")
                .build();
    }

    /**
     * 冲突检测器专用 Client（D76）
     */
    @Bean("conflictDetectorClient")
    public ChatClient conflictDetectorClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一位客观、严谨的意见冲突分析专家。")
                .build();
    }

    /**
     * 仲裁者专用 Client（D76）
     */
    @Bean("conflictArbiterClient")
    public ChatClient conflictArbiterClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一位经验丰富、决断力强的仲裁者。")
                .build();
    }
}