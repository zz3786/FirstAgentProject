package org.example.multiagent.reflection;

/**
 * 反思元数据的 context key（D71）
 *
 * <h3>为什么需要这个类</h3>
 * <p>
 * {@code SelfReflectionAdvisor} 把反思结果写入
 * {@code ChatClientResponse.context()}，{@code MultiAgentFacade} 从中读取。
 * 两处使用同一组 key——必须集中定义，避免拼写错误。
 *
 * <h3>为什么用 "reflection." 前缀</h3>
 * <p>
 * {@code ChatClientResponse.context()} 是 Map，可能有其他 Advisor 写入
 * 自己的 key。加命名空间前缀避免冲突——这和执行环境的 context key 设计一致
 * （如 {@code ChatMemory.CONVERSATION_ID} 是 {@code chat_memory_conversation_id}）。
 *
 * <h3>为什么不放 public static final 到 Advisor 里</h3>
 * <p>
 * Advisor 是 Spring Bean，被 Facade 依赖时不应耦合"Advisor 类"本身——
 * 常量类只暴露契约，不暴露实现。
 */
public final class ReflectionMetadata {

    /** 反思后的最终答案——Facade 用它覆盖原始 output */
    public static final String FINAL_ANSWER = "reflection.finalAnswer";

    /** 反思轮次——0 表示未反思 */
    public static final String ROUNDS = "reflection.rounds";

    /** 最终质量分——-1 表示未评估 */
    public static final String SCORE = "reflection.score";

    /** 最终评估是否接受 */
    public static final String ACCEPTED = "reflection.accepted";

    /** 反思阶段总耗时（毫秒） */
    public static final String COST_MS = "reflection.costMs";

    private ReflectionMetadata() {}
}