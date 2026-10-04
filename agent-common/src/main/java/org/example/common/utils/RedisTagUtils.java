package org.example.common.utils;

/**
 * RediSearch TAG 字段转义工具
 * <p>
 * <b>为什么需要它</b>：
 * RediSearch 的 TAG 类型把 {@code - : , . | 空格} 等当保留字符，
 * 值里含这些字符不转义 → 要么语法报错（如 filter 里的 {@code conversation_id}），
 * 要么匹配不上（如 {@code user_id}）。
 * <p>
 * <b>核心规则（务必遵守）</b>：
 * <b>存和查必须调同一个方法</b>。存的时候转了、查的时候不转
 * （或反之）→ 字符串不相等 → 永远匹配不上。
 * <p>
 * <b>为什么不用"加反斜杠"的方式</b>：
 * RediSearch 原生转义是 {@code hospital-a\:user}，但 Spring AI 的
 * RedisVectorStore 内部还会做一层转义——双层转义难以预测最终形态。
 * 统一成"非字母数字下划线 → 下划线"最简单：幂等、可读、无歧义。
 * <p>
 * <b>语义损失</b>：
 * {@code hospital-a:user-alice} 和 {@code hospital_a:user_alice}
 * 会转义成同一个值——理论上存在哈希碰撞可能，
 * 但我们的 key 都由系统生成、格式受控，实际不会碰撞。
 */
public final class RedisTagUtils {

    /** 未提供值时的哨兵——保证 filter 里的字段永远有确定值 */
    public static final String DEFAULT_VALUE = "default";

    private RedisTagUtils() {}

    /**
     * 转义 RediSearch TAG 字段值
     * <p>
     * 规则：非 [a-zA-Z0-9_] 字符全部替换成下划线。
     * <pre>
     * "hospital-a:user-alice"  →  "hospital_a_user_alice"
     * "user-B"                 →  "user_B"
     * "c383288e"               →  "c383288e"          （已是合法字符，不变）
     * null                     →  "default"
     * ""                       →  "default"
     * </pre>
     */
    public static String escape(String s) {
        if (s == null || s.isBlank()) {
            return DEFAULT_VALUE;
        }
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}