package org.example.rag.retrieval.model;

import java.util.List;

/**
 * 用户检索画像
 * <p>
 * <b>职责</b>：承载"个性化检索参数"——从用户历史行为推断出来的软偏好。
 * <p>
 * <b>关键设计：为什么是 record 而非 class</b>：
 * 画像一旦构造就不可变——参数映射阶段不会修改它。
 * record 保证"不可变 + 值语义"——避免并发修改。
 * <p>
 * <b>关键设计：为什么所有字段都有默认值</b>：
 * 新用户、冷启动、画像过时——这些场景下 profile 应退化为"无偏好"。
 * 用 empty() 统一表示——所有偏移为 0、所有列表为空。
 * <p>
 * <b>硬约束：所有偏移都有上限</b>：
 * 权重偏移 |weightBiasVec| ≤ 0.15，加分系数 ≤ 1.20——防止画像失控。
 */
public record RetrievalProfile(
        /** 偏好部门（软加分，不是硬过滤） */
        List<String> preferredDepartments,
        /** 偏好内容类型（制度/合同/报告） */
        List<String> preferredDocTypes,
        /** 向量权重偏移：正值=更依赖语义，负值=更依赖关键词 */
        double weightBiasVec,
        /** 关键词权重偏移：与 weightBiasVec 相反 */
        double weightBiasKw,
        /** topK 偏移（暂未启用，为后续扩展预留） */
        int topKBias
) {

    /** 偏移上限——防止画像失控 */
    private static final double MAX_WEIGHT_BIAS = 0.15;
    private static final double MAX_FILTER_BOOST = 1.20;

    /**
     * 空画像——无任何偏好
     * <p>
     * 冷启动 / 画像过期 / 样本不足时使用。
     */
    public static RetrievalProfile empty() {
        return new RetrievalProfile(List.of(), List.of(), 0.0, 0.0, 0);
    }

    /** 是否为空画像 */
    public boolean isEmpty() {
        return preferredDepartments.isEmpty()
                && preferredDocTypes.isEmpty()
                && weightBiasVec == 0.0
                && weightBiasKw == 0.0
                && topKBias == 0;
    }

    /** 是否有偏好部门 */
    public boolean hasPreferredDepartments() {
        return !preferredDepartments.isEmpty();
    }

    /**
     * 应用权重偏移——返回调整后的向量权重
     * <p>
     * <b>关键设计：clamp 上限</b>
     * 用户画像偏移最多 ±0.15——防止"用户问过财务部就永远只看财务部"的自我强化偏见。
     * <p>
     * <b>为什么返回单个值而不是改多个</b>：
     * w_vec 和 w_kw 之和应为 1.0——只调 w_vec，w_kw = 1 - w_vec 自动跟随。
     * 调用方拿到最终 w_vec，w_kw 自然算出。
     *
     * @param baseVectorWeight 配置里的默认向量权重（如 0.7）
     * @return 调整后的向量权重（clamp 到 [0.3, 0.9]）
     */
    public double applyWeightBias(double baseVectorWeight) {
        // ① 应用偏移 + clamp
        double bias = Math.max(-MAX_WEIGHT_BIAS, Math.min(MAX_WEIGHT_BIAS, weightBiasVec));
        double adjusted = baseVectorWeight + bias;

        // ② 二次 clamp——保证权重在合理区间
        return Math.max(0.3, Math.min(0.9, adjusted));
    }

    /**
     * 获取偏好加分系数
     * <p>
     * 对符合偏好部门/类型的文档——融合分数乘以这个系数。
     * clamp 到 [1.0, 1.20]——最多加 20%。
     */
    public double filterBoost() {
        if (preferredDepartments.isEmpty() && preferredDocTypes.isEmpty()) {
            return 1.0;
        }
        return MAX_FILTER_BOOST;
    }
}