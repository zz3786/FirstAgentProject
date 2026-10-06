package org.example.core.retry;

import java.util.Set;
import java.util.function.Predicate;

/**
 * 重试条件判断
 * <p>
 * <b>什么异常该重试？</b>
 * <ul>
 *   <li>✅ 网络超时、连接失败——临时故障</li>
 *   <li>✅ 429 限流——等会再来</li>
 *   <li>✅ 5xx 服务端错误——对方可能自愈</li>
 *   <li>❌ 401/403 认证失败——重试无意义</li>
 *   <li>❌ 400 参数错误——重试还会错</li>
 *   <li>❌ 业务异常——重试不能解决业务问题</li>
 * </ul>
 * <p>
 * <b>核心原则</b>：<b>只重试"可能自己好起来"的异常</b>。
 */
public final class RetryPredicates {

    private RetryPredicates() {}

    /**
     * 默认重试条件
     * <p>
     * 排除"不可重试"的异常类型——其余都重试。
     */
    public static Predicate<Throwable> defaultPredicate() {
        return t -> {
            if (t == null) {
                return false;
            }
            // 排除"业务异常"和"参数错误"
            if (t instanceof IllegalArgumentException) {
                return false;
            }
            if (t instanceof IllegalStateException) {
                return false;
            }
            if (t instanceof UnsupportedOperationException) {
                return false;
            }
            if (t instanceof NullPointerException) {
                return false;
            }
            // 其余（IO 异常、超时、网络异常）都重试
            return true;
        };
    }

    /**
     * 只重试指定异常类型（含子类）
     */
    public static Predicate<Throwable> onlyTypes(Class<? extends Throwable>... types) {
        Set<Class<? extends Throwable>> set = Set.of(types);
        return t -> t != null && set.stream().anyMatch(c -> c.isInstance(t));
    }

    /**
     * 不重试指定异常类型（含子类）
     */
    public static Predicate<Throwable> excludeTypes(Class<? extends Throwable>... types) {
        Set<Class<? extends Throwable>> set = Set.of(types);
        return t -> t != null && set.stream().noneMatch(c -> c.isInstance(t));
    }
}