package org.example.multiagent.conflict.resolver;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.conflict.ConflictResolver;
import org.example.multiagent.conflict.model.ArbiterVerdict;
import org.example.multiagent.conflict.model.ConflictContext;
import org.example.multiagent.conflict.model.ConflictResolution;
import org.example.multiagent.conflict.model.Opinion;
import org.example.multiagent.model.AgentResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 仲裁者策略（D76）
 *
 * <h3>机制</h3>
 * <ol>
 *   <li>把"原始专家意见 + 冲突总结"喂给仲裁者 LLM</li>
 *   <li>LLM 输出裁决：decision + reasoning + winningRole + confidence</li>
 *   <li>包装成 {@code ConflictResolution} 返回</li>
 * </ol>
 *
 * <h3>与投票的区别</h3>
 * <table>
 *   <tr><th>维度</th><th>投票</th><th>仲裁</th></tr>
 *   <tr><td>决策者</td><td>少数服从多数</td><td>专门的仲裁 LLM</td></tr>
 *   <tr><td>LLM 调用</td><td>0 次（纯 Java）</td><td>1 次</td></tr>
 *   <tr><td>处理"平票"</td><td>返回 unresolved</td><td>强制裁决</td></tr>
 *   <tr><td>考虑专家权威</td><td>否（所有票均等）</td><td>是（LLM 可判断谁更权威）</td></tr>
 * </table>
 *
 * <h3>Prompt 设计要点</h3>
 * <ul>
 *   <li>要求仲裁者"站在用户目标视角"——不是为了学术正确，而是为用户决策</li>
 *   <li>明确允许"条件性裁决"——如"如果用户预算充足选 A，否则选 B"</li>
 *   <li>低信心时必须在裁决文本里说明不确定性</li>
 * </ul>
 *
 * <h3>异常降级</h3>
 * <p>
 * LLM 调用失败或返回格式错误时——返回 {@code ConflictResolution.unresolved}，
 * 让聚合策略自己处理"存在未解决分歧"的场景。
 */
@Slf4j
public class ArbiterResolver implements ConflictResolver {

    private final ChatClient arbiterClient;
    private final BeanOutputConverter<ArbiterVerdict> converter;

    public ArbiterResolver(ChatClient arbiterClient) {
        this.arbiterClient = arbiterClient;
        this.converter = new BeanOutputConverter<>(ArbiterVerdict.class);
    }

    @Override
    public String name() {
        return "ARBITER";
    }

    @Override
    public ConflictResolution resolve(ConflictContext context) {
        long start = System.currentTimeMillis();

        try {
            String prompt = buildArbiterPrompt(context);
            String raw = arbiterClient.prompt().user(prompt).call().content();

            if (raw == null || raw.isBlank()) {
                long cost = System.currentTimeMillis() - start;
                log.warn("[Arbiter] LLM 返回空");
                return ConflictResolution.unresolved(
                        name(), "仲裁者返回空", context.analysis().severity(), cost);
            }

            ArbiterVerdict verdict = converter.convert(cleanJson(raw));
            long cost = System.currentTimeMillis() - start;

            if (verdict == null) {
                return ConflictResolution.unresolved(
                        name(), "解析失败", context.analysis().severity(), cost);
            }

            String resolution = verdict.decision();
            if (verdict.reasoning() != null && !verdict.reasoning().isBlank()) {
                resolution = resolution + "\n\n【理由】" + verdict.reasoning();
            }

            log.info("[Arbiter] 裁决完成: decision=[{}], winningRole={}, confidence={}, costMs={}",
                    truncate(verdict.decision(), 50),
                    verdict.winningRole(),
                    String.format("%.2f", verdict.confidence()),
                    cost);

            return ConflictResolution.resolved(
                    name(), resolution, verdict.winningRole(),
                    context.analysis().severity(),
                    verdict.confidence(),
                    cost);

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[Arbiter] 仲裁异常", e);
            return ConflictResolution.unresolved(
                    name(), "仲裁异常: " + e.getMessage(),
                    context.analysis().severity(), cost);
        }
    }

    // ==================== 内部 ====================

    private String buildArbiterPrompt(ConflictContext context) {
        String schema = converter.getFormat();

        // 专家意见——用原始 responses（比 opinions 更全面）
        String opinionsText = context.responses().stream()
                .filter(AgentResponse::success)
                .map(r -> String.format("【%s】\n%s\n",
                        r.role().getDisplayName(),
                        truncate(r.output(), 1500)))
                .collect(Collectors.joining("\n"));

        // 冲突总结
        String conflictSummary = context.analysis().summary();
        String opinionsStructured = context.analysis().opinions().stream()
                .filter(Opinion::isMeaningful)
                .map(op -> String.format("- %s: %s",
                        op.role().getDisplayName(), op.stance()))
                .collect(Collectors.joining("\n"));

        return """
                ================ 角色 ================
                你是多智能体系统的【仲裁者】。多位领域专家对同一问题给出了
                不同意见，你需要站在【用户目标视角】做出裁决。
                
                ================ 用户原始问题 ================
                %s
                
                ================ 专家意见 ================
                %s
                
                ================ 已识别的冲突 ================
                %s
                
                立场摘要：
                %s
                
                ================ 裁决原则 ================
                1. 【用户视角优先】不是为了"学术正确"，是为了帮用户做出决策
                2. 【考虑权威性】在某些领域，某位专家的意见可能更权威
                   （如合规问题→法务更权威，成本问题→财务更权威）
                3. 【允许条件性裁决】如果答案依赖未明确的变量（如预算），
                   可以给出条件性裁决："如果 X 则 A，否则 B"
                4. 【承认不确定】如果双方证据都不充分，明确说"目前无法确定"
                   —— 不要为了给答案而给答案
                5. 【简洁有力】裁决控制在 200 字以内
                
                ================ 输出格式 ================
                只输出 JSON，不要任何解释文字。
                Schema：
                %s
                
                ================ 现在开始裁决 ================
                """.formatted(
                context.goal(),
                opinionsText,
                conflictSummary == null || conflictSummary.isBlank() ? "（无详细总结）" : conflictSummary,
                opinionsStructured,
                schema);
    }

    private String cleanJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("^```[a-zA-Z]*\\s*", "")
                    .replaceAll("\\s*```\\s*$", "");
        }
        return s;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}