package org.example.multiagent.aggregation.strategy;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.aggregation.AggregationStrategy;
import org.example.multiagent.aggregation.model.AggregationContext;
import org.example.multiagent.aggregation.model.AggregationResult;
import org.example.multiagent.model.AgentResponse;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

/**
 * LLM 合成策略（D75 默认）
 *
 * <h3>做什么</h3>
 * <p>
 * 把多个 Worker 响应交给 LLM——要求它合成一段<b>连贯的答案</b>，
 * 而不是"财务说... 技术说..."的罗列。
 *
 * <h3>关键要求（Prompt 工程）</h3>
 * <ol>
 *   <li><b>不要罗列</b>——融合观点成连贯语言</li>
 *   <li><b>保留分歧</b>——专家意见冲突时说明"存在不同看法"</li>
 *   <li><b>说明缺失</b>——某视角失败时明确"该视角未覆盖"</li>
 *   <li><b>结构清晰</b>——用 markdown 增强可读性</li>
 * </ol>
 *
 * <h3>为什么摘要长度限制在 2000 字</h3>
 * <p>
 * 每个 Worker 的输出可能很长（几千字）——全塞进聚合 Prompt 会：
 * <ul>
 *   <li>超出 LLM 上下文窗口（叠加多个 Worker 时）</li>
 *   <li>增加成本（Token 按量计费）</li>
 *   <li>引入噪音（长回答的冗余部分会干扰合成）</li>
 * </ul>
 * 2000 字已能覆盖核心信息——Worker 输出超过时截断。
 *
 * <h3>异常降级</h3>
 * <p>
 * LLM 调用失败时——<b>不抛异常</b>，返回"用 PlainConcat 结果兜底"。
 * 这样即使 LLM 挂了，用户至少能拿到"各专家的原始意见"。
 */
@Slf4j
public class LlmSynthesisStrategy implements AggregationStrategy {

    /** 单 Worker 结果注入 Prompt 的最大字符数 */
    private static final int MAX_RESULT_CHARS = 2000;

    private final ChatClient synthesizerClient;

    public LlmSynthesisStrategy(ChatClient synthesizerClient) {
        this.synthesizerClient = synthesizerClient;
    }

    @Override
    public String name() {
        return "LLM_SYNTHESIS";
    }

    @Override
    public AggregationResult aggregate(AggregationContext context) {
        long start = System.currentTimeMillis();

        try {
            String opinions = buildOpinionsText(context);
            String prompt = buildPrompt(context.goal(), opinions);

            String answer = synthesizerClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            long cost = System.currentTimeMillis() - start;

            if (answer == null || answer.isBlank()) {
                log.warn("[LlmSynthesis] LLM 返回空——降级为拼接");
                return fallbackConcat(context, "LLM 返回空", cost);
            }

            log.info("[LlmSynthesis] 完成: 输入 {} 条，输出 {} 字，耗时 {}ms",
                    context.size(), answer.length(), cost);

            return AggregationResult.ok(answer, name(), cost,
                    Map.of("inputCount", context.size(),
                            "outputLength", answer.length()));

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[LlmSynthesis] 失败——降级为拼接", e);
            return fallbackConcat(context, e.getMessage(), cost);
        }
    }

    // ==================== 内部 ====================

    private String buildOpinionsText(AggregationContext context) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (AgentResponse resp : context.responses()) {
            sb.append("【专家 ").append(idx++).append("：")
                    .append(resp.role().getDisplayName()).append("】\n");

            if (resp.success()) {
                sb.append(truncate(resp.output(), MAX_RESULT_CHARS)).append("\n\n");
            } else {
                sb.append("（未成功：").append(resp.error()).append("）\n\n");
            }
        }
        return sb.toString();
    }

    private String buildPrompt(String goal, String opinions) {
        return """
                ================ 场景 ================
                你是一位主管，刚刚收到了多位领域专家对同一问题的独立分析。
                你的任务是【整合】这些意见，给用户一个<b>连贯、完整</b>的答复。
                
                ================ 原始目标 ================
                %s
                
                ================ 专家意见 ================
                %s
                
                ================ 整合要求 ================
                1. 【不要罗列】不要写"财务专家说...，技术专家说..."
                   ——而是把观点融合成一段连贯的话
                2. 【保留分歧】如果专家意见有冲突，说明"存在不同看法"，并列出
                3. 【说明缺失】如果某个视角的专家没有给出有效答案，明确说明
                   "该视角暂未覆盖"——不要让用户误以为答案完整
                4. 【结构清晰】适当使用列表、加粗等 markdown 增强可读性
                5. 【不超过 800 字】——用户要的是"结论"，不是"论文"
                
                ================ 现在开始整合 ================
                """.formatted(goal, opinions);
    }

    /**
     * 降级——纯 Java 拼接
     */
    private AggregationResult fallbackConcat(AggregationContext context,
                                             String reason,
                                             long cost) {
        StringBuilder sb = new StringBuilder("综合意见（降级模式）：\n\n");
        for (AgentResponse resp : context.responses()) {
            sb.append("【").append(resp.role().getDisplayName()).append("】\n");
            if (resp.success()) {
                sb.append(truncate(resp.output(), MAX_RESULT_CHARS));
            } else {
                sb.append("（该视角未成功回答：").append(resp.error()).append("）");
            }
            sb.append("\n\n");
        }
        return AggregationResult.fail(sb.toString(), name(), cost, reason);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}