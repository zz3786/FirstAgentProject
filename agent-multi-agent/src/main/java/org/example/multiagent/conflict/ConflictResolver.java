package org.example.multiagent.conflict;

import org.example.multiagent.conflict.model.ConflictContext;
import org.example.multiagent.conflict.model.ConflictResolution;

/**
 * 冲突解决器接口（D76）
 *
 * <h3>两个标准实现</h3>
 * <ul>
 *   <li>{@code MajorityVoteResolver}——投票</li>
 *   <li>{@code ArbiterResolver}——仲裁</li>
 * </ul>
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li>{@code name()} 返回稳定的策略名——匹配 yml 里的值</li>
 *   <li>{@code resolve()} <b>不应抛异常</b>——失败时返回
 *       {@code ConflictResolution.unresolved(...)}</li>
 * </ul>
 *
 * <h3>为什么要约定"不抛异常"</h3>
 * <p>
 * 冲突处理是"锦上添花"——即使失败，也应该让聚合策略正常合成。
 * 抛异常会中断整个 Supervisor 流程——与"尽力返回答案"的定位不符。
 */
public interface ConflictResolver {

    /**
     * 策略名——用于 yml 配置匹配
     */
    String name();

    /**
     * 处理冲突
     *
     * @param context 冲突上下文
     * @return 决议——永不为 null
     */
    ConflictResolution resolve(ConflictContext context);
}