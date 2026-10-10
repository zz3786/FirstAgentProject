package org.example.multiagent.model;

/**
 * 反思评估结果（D71）
 *
 * <h3>为什么独立成 record 而不是内嵌在 Advisor 里</h3>
 * <p>
 * 反思评估未来可能演化成"多评审员 + 加权投票"（D76 会做类似的事）。
 * 独立类型便于：
 * <ul>
 *   <li>单测时直接构造各种场景（接受、拒绝、异常）</li>
 *   <li>未来把评估逻辑抽成 {@code QualityEvaluator} 接口</li>
 *   <li>D76 的仲裁者复用同一套"评估结果"语义</li>
 * </ul>
 *
 * <h3>字段语义</h3>
 * <ul>
 *   <li>{@code accepted}——是否直接接受（true 表示不需要继续反思）</li>
 *   <li>{@code qualityScore}——0.0~1.0。评估失败时用 {@code -1}</li>
 *   <li>{@code critique}——批评意见。accepted=true 时为 null</li>
 *   <li>{@code revisedAnswer}——改进后的答案。accepted=true 时为 null</li>
 * </ul>
 *
 * <h3>为什么不带"评估来源"</h3>
 * <p>
 * 当前只有一种来源（LLM 评审）。如果未来加"规则评审"，
 * 用 {@code metadata} 或新建 {@code EvaluatedOutcome} record 扩展，
 * 不要在这里加 nullable 字段。
 */
public record ReflectionOutcome(
        boolean accepted,
        double qualityScore,
        String critique,
        String revisedAnswer
) {

    /**
     * 接受——不再反思
     */
    public static ReflectionOutcome accept(double score) {
        return new ReflectionOutcome(true, score, null, null);
    }

    /**
     * 拒绝——携带批评意见和改进答案
     *
     * @param score         质量分
     * @param critique      批评意见（可为空，但建议填）
     * @param revisedAnswer 改进后的答案（可为空表示"只批评不改"）
     */
    public static ReflectionOutcome reject(double score, String critique, String revisedAnswer) {
        return new ReflectionOutcome(false, score, critique, revisedAnswer);
    }

    /** 是否有改进答案——Advisor 用这个决定是否替换 */
    public boolean hasRevisedAnswer() {
        return revisedAnswer != null && !revisedAnswer.isBlank();
    }
}