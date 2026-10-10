package org.example.multiagent.reflection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * LLM 评审员的输出（D71）
 *
 * <h3>为什么单独定义</h3>
 * <p>
 * 这是"LLM 契约"——决定 Prompt 里 schema 提示的内容。
 * 和 {@code ReflectionOutcome}（内部评估结果）分离的好处：
 * <ul>
 *   <li>LLM 契约可以演化（加字段），不影响内部逻辑</li>
 *   <li>内部结果可以加"来源"等字段，不必污染 LLM 契约</li>
 *   <li>单测时 mock LLM 输出只需构造 JSON，不用构造业务对象</li>
 * </ul>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code accepted}——LLM 认为是否接受当前回答</li>
 *   <li>{@code qualityScore}——质量分 0.0~1.0</li>
 *   <li>{@code critique}——批评意见（accepted=true 时可为空）</li>
 *   <li>{@code revisedAnswer}——完整修订后的答案（accepted=true 时可为空）</li>
 * </ul>
 *
 * <h3>为什么 revisedAnswer 是"完整答案"而不是"修改说明"</h3>
 * <p>
 * 让 LLM 输出完整答案的好处：<b>不需要第二轮 LLM 调用</b>。
 * 如果让 LLM 只输出"修改建议"，还需要再调一次 LLM 让它按建议重写——
 * 成本翻倍且增加不确定性。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReflectionVerdict(
        boolean accepted,
        double qualityScore,
        String critique,
        String revisedAnswer
) {
    /**
     * 兜底构造——LLM 输出缺字段时的默认值
     */
    public static ReflectionVerdict safeDefault() {
        return new ReflectionVerdict(false, 0.5, "评审失败", null);
    }
}