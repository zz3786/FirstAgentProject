package org.example.multiagent.reflection;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.config.MultiAgentProperties;
import org.example.multiagent.model.ReflectionOutcome;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * 自我反思 Advisor（D71）
 *
 * <h3>它做什么</h3>
 * <pre>
 *   ① 让 chain 走完整链，拿到初步回答 A0
 *   ② 用独立 reflectorClient 评估 A0：
 *        accepted=true  → 直接用 A0，结束
 *        accepted=false → 得到"批评意见"和"改进答案 A1"
 *   ③ A1 作为新答案，回到 ② ——最多 maxRounds 轮
 *   ④ 把最终答案写入 response.context()
 * </pre>
 *
 * <h3>为什么用独立的 reflectorClient 而不是 chain.nextCall 递归</h3>
 * <p>
 * {@code chain.nextCall()} 会重新走整条链——包括记忆、RAG、工具调用。
 * 反思阶段不需要这些——它只是"审阅答案"，不需要重新检索。
 * <ul>
 *   <li>节省 Token——不重复注入 RAG 资料</li>
 *   <li>加速——不重复调 embedding</li>
 *   <li>职责清晰——reflectorClient 只做评审，无副作用</li>
 * </ul>
 *
 * <h3>为什么反思答案写进 context 而不是替换 response.text</h3>
 * <p>
 * 替换 {@code ChatClientResponse.chatResponse()} 里的文本需要重建
 * {@code ChatResponse → Generation → AssistantMessage} 三层对象——
 * 代码繁琐且易错（Spring AI 内部可能读取 metadata、toolCalls 等字段）。
 * <p>
 * 写入 context 的好处：
 * <ul>
 *   <li>不破坏原始 response 的结构</li>
 *   <li>调用方（Facade）显式决定用哪个</li>
 *   <li>下游 Advisor 仍能看到原始回答（如果需要）</li>
 * </ul>
 *
 * <h3>流式调用为什么不反思</h3>
 * <p>
 * 流式响应是"边生成边推送"——反思必须在答案完成后进行，
 * 此时 token 已经发到客户端了，无法撤回。
 * 强行支持会变成"缓冲整个流 → 反思 → 一次性重发"——破坏流式的意义。
 * <p>
 * 反思是"批处理/后台任务"场景的能力，UI 交互用非流式即可。
 *
 * <h3>Order 为什么是最外层</h3>
 * <p>
 * {@code HIGHEST_PRECEDENCE + 1}——比所有业务 Advisor 都小。
 * 效果：
 * <ul>
 *   <li>请求阶段：最先看到 request（此时只是透传）</li>
 *   <li>响应阶段：最后看到 response（此时是最终答案）</li>
 * </ul>
 * 如果 order 大（更内层），反思的是"记忆注入后"的中间态——语义错误。
 */
@Slf4j
public class SelfReflectionAdvisor implements CallAdvisor, StreamAdvisor {

    /** 快检阈值——答案短于此长度直接判定不合格，跳过 LLM 评估 */
    private static final int MIN_ANSWER_LENGTH_FOR_LLM_CHECK = 20;

    /** 快检阈值——答案含"无法回答"等否定词且短于此长度时判定不合格 */
    private static final int MIN_ANSWER_LENGTH_FOR_NEGATIVE = 100;

    private final ChatClient reflectorClient;
    private final MultiAgentProperties props;
    private final BeanOutputConverter<ReflectionVerdict> verdictConverter;

    public SelfReflectionAdvisor(ChatClient reflectorClient,
                                 MultiAgentProperties props) {
        this.reflectorClient = reflectorClient;
        this.props = props;
        this.verdictConverter = new BeanOutputConverter<>(ReflectionVerdict.class);
    }

    @Override
    public String getName() {
        return "SelfReflectionAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    // ==================== 同步调用 ====================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();

        // ① 让链拿到初步回答
        ChatClientResponse primary = chain.nextCall(request);

        MultiAgentProperties.Reflection cfg = props.getReflection();
        if (!cfg.isEnabled()) {
            return primary;
        }

        // ② 提取问题与答案
        String query = extractLastUserMessage(request);
        String initialAnswer = extractText(primary);
        if (query.isBlank() || initialAnswer.isBlank()) {
            return primary;
        }

        // ③ 反思循环
        String bestAnswer = initialAnswer;
        double bestScore = -1.0;
        int round = 0;
        boolean finalAccepted = false;

        while (round < cfg.getMaxRounds()) {
            ReflectionOutcome outcome = doReflect(query, bestAnswer, round);

            // 评估失败——接受现有答案，不阻断
            if (outcome == null) {
                log.warn("[SelfReflection] 第 {} 轮评估失败——接受当前答案", round + 1);
                break;
            }

            bestScore = outcome.qualityScore();

            // 接受：LLM 判定接受 OR 分数够高
            if (outcome.accepted() || bestScore >= cfg.getQualityThreshold()) {
                finalAccepted = true;
                log.info("[SelfReflection] 第 {} 轮通过——accepted={}, score={}",
                        round + 1, outcome.accepted(),
                        String.format("%.2f", bestScore));
                break;
            }

            // 拒绝：如果有改进答案则替换，进入下一轮
            if (outcome.hasRevisedAnswer()) {
                log.info("[SelfReflection] 第 {} 轮拒绝——score={}, critique={}, 使用改进答案",
                        round + 1, String.format("%.2f", bestScore),
                        truncate(outcome.critique(), 100));
                bestAnswer = outcome.revisedAnswer();
            } else {
                log.info("[SelfReflection] 第 {} 轮拒绝但无改进答案——接受当前答案",
                        round + 1);
                break;
            }
            round++;
        }

        long totalCost = System.currentTimeMillis() - start;
        long reflectionCost = totalCost - extractPrimaryCost(primary);
        log.info("[SelfReflection] 完成: query=[{}], 反思轮次={}, 最终分数={}, 反思耗时={}ms",
                truncate(query, 30), round,
                String.format("%.2f", bestScore), reflectionCost);

        // ④ 把反思结果写入 context
        Map<String, Object> newContext = new HashMap<>(primary.context());
        newContext.put(ReflectionMetadata.FINAL_ANSWER, bestAnswer);
        newContext.put(ReflectionMetadata.ROUNDS, round);
        newContext.put(ReflectionMetadata.SCORE, bestScore);
        newContext.put(ReflectionMetadata.ACCEPTED, finalAccepted);
        newContext.put(ReflectionMetadata.COST_MS, reflectionCost);

        return primary.mutate().context(newContext).build();
    }

    // ==================== 流式调用 ====================

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request,
                                                 StreamAdvisorChain chain) {
        if (props.getReflection().isEnabled()) {
            log.debug("[SelfReflection] 流式调用——反思已跳过（流式不支持反思）");
        }
        return chain.nextStream(request);
    }

    // ==================== 内部方法 ====================

    /**
     * 单轮反思
     *
     * @return 评估结果；任何异常返回 {@code null}（表示"放弃评估"）
     */
    private ReflectionOutcome doReflect(String query, String answer, int round) {
        try {
            // ① 快速规则检查——命中则跳过 LLM
            ReflectionOutcome quick = quickCheck(answer);
            if (quick != null) {
                return quick;
            }

            // ② LLM 评估
            String prompt = buildReflectionPrompt(query, answer, round);
            String raw = reflectorClient.prompt().user(prompt).call().content();
            if (raw == null || raw.isBlank()) {
                log.warn("[SelfReflection] reflector 返回空");
                return null;
            }

            // ③ 解析 verdict
            ReflectionVerdict verdict = parseVerdict(raw);
            if (verdict == null) {
                return null;
            }

            // ④ 转换为内部结果
            if (verdict.accepted()) {
                return ReflectionOutcome.accept(verdict.qualityScore());
            }
            return ReflectionOutcome.reject(
                    verdict.qualityScore(),
                    verdict.critique(),
                    verdict.revisedAnswer());

        } catch (Exception e) {
            log.warn("[SelfReflection] 反思异常", e);
            return null;
        }
    }

    /**
     * 快速规则检查
     * <p>
     * 命中时直接返回结果，跳过 LLM 评估——省 Token 省时间。
     * 未命中返回 {@code null}，进入 LLM 评估。
     */
    private ReflectionOutcome quickCheck(String answer) {
        if (answer == null || answer.isBlank()) {
            return ReflectionOutcome.reject(0.0, "回答为空",
                    "请给出实质性的回答");
        }
        if (answer.length() < MIN_ANSWER_LENGTH_FOR_LLM_CHECK) {
            return ReflectionOutcome.reject(0.3, "回答过短",
                    "请给出更详细、更完整的回答");
        }

        String lower = answer.toLowerCase();
        boolean hasNegative = lower.contains("无法回答")
                || lower.contains("我不知道")
                || lower.contains("i cannot")
                || lower.contains("i don't know");
        if (hasNegative && answer.length() < MIN_ANSWER_LENGTH_FOR_NEGATIVE) {
            return ReflectionOutcome.reject(0.4, "回答未提供实质内容",
                    "请尝试从其他角度回答，或明确说明为什么无法回答");
        }

        // 通过快速检查——进入 LLM 评估
        return null;
    }

    /**
     * 构造反思 Prompt
     */
    private String buildReflectionPrompt(String query, String answer, int round) {
        String schema = verdictConverter.getFormat();
        return """
                你是一位严格但公正的回答质量评审员。你的任务是评估下面的"用户问题-助手回答"对，
                并决定是否需要改进。
                
                ================ 用户问题 ================
                %s
                
                ================ 助手回答（第 %d 版） ================
                %s
                
                ================ 评估维度 ================
                1. **相关性**：是否真的回答了用户问题？有没有答非所问？
                2. **完整性**：是否有重要信息缺失？是否只答了一半？
                3. **准确性**：是否有事实性错误？是否有明显的逻辑矛盾？
                4. **实用性**：用户看完是否能直接使用？还是需要再追问？
                
                ================ 判定标准 ================
                - 满足以上 4 条中的 3 条以上，且无明显错误 → accepted=true
                - 有明显缺陷（答非所问、严重缺失、事实错误） → accepted=false
                
                ================ 如果 rejected ================
                必须给出 **修订后的完整答案**（不是修改说明，是最终版本），
                让用户可以直接使用。修订时要：
                1. 保留原答案中正确的部分
                2. 修复缺失和错误的部分
                3. 保持原有风格（简洁/详细/正式等）
                
                ================ 输出格式 ================
                只输出一个 JSON 对象，不要任何解释文字，不要 markdown 代码块。
                Schema：
                %s
                
                字段说明：
                - accepted       : boolean，是否接受当前回答
                - qualityScore   : 0.0~1.0，回答质量分数
                - critique       : string，批评意见（accepted=true 时可为空）
                - revisedAnswer  : string，修订后的完整答案（accepted=true 时可为空）
                
                ================ 现在开始评估 ================
                """.formatted(query, round + 1, answer, schema);
    }

    /**
     * 解析 LLM 输出为 ReflectionVerdict
     * <p>
     * 兜底处理 markdown 代码块包裹——LLM 有时会输出 {@code ```json ... ```}。
     */
    private ReflectionVerdict parseVerdict(String raw) {
        try {
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\s*", "")
                        .replaceAll("\\s*```\\s*$", "");
            }
            return verdictConverter.convert(cleaned);
        } catch (Exception e) {
            log.warn("[SelfReflection] 解析 verdict 失败: raw=[{}]",
                    truncate(raw, 200), e);
            return null;
        }
    }

    /**
     * 从 response 提取文本
     */
    private String extractText(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null) {
            return "";
        }
        String text = response.chatResponse().getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * 提取最后一条 USER 消息——即"当前问题"
     */
    private String extractLastUserMessage(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return "";
        }
        return request.prompt().getInstructions().stream()
                .filter(m -> "USER".equals(m.getMessageType().name()))
                .map(Message::getText)
                .reduce((a, b) -> b)
                .orElse("");
    }

    /**
     * 从 response context 中提取 primary 耗时（如果有）
     * <p>
     * 目前无来源——返回 0。D77 会从 trace 中取。
     */
    private long extractPrimaryCost(ChatClientResponse primary) {
        return 0L;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}