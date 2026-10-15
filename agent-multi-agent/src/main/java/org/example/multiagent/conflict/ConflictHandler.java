package org.example.multiagent.conflict;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.conflict.model.ConflictAnalysis;
import org.example.multiagent.conflict.model.ConflictContext;
import org.example.multiagent.conflict.model.ConflictResolution;
import org.example.multiagent.config.MultiAgentProperties;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冲突处理门面（D76）
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>调 {@code ConflictDetector} 检测冲突</li>
 *   <li>无冲突 → 返回 {@code ConflictResolution.noConflict()}</li>
 *   <li>有冲突 → 按 yml 配置选 Resolver，执行并返回决议</li>
 * </ol>
 *
 * <h3>为什么有这一层</h3>
 * <p>
 * 把"检测"和"解决"两个独立动作编排在一起——
 * <ul>
 *   <li>{@code SupervisorAgent} 只需调一次 {@code handle()}——不用感知内部流程</li>
 *   <li>Detector 和 Resolver 各自独立——可测试、可替换</li>
 *   <li>未来可加"多次投票"、"级联仲裁"等复杂流程——只改本类</li>
 * </ul>
 *
 * <h3>Map 装配 Resolver</h3>
 * <p>
 * 和 {@code ResultAggregator} 一样的做法——按 {@code name()} 索引，
 * yml 里的策略名直接作为 map key。
 */
@Slf4j
public class ConflictHandler {

    private final ConflictDetector detector;
    private final Map<String, ConflictResolver> resolvers;
    private final MultiAgentProperties props;

    public ConflictHandler(ConflictDetector detector,
                           List<ConflictResolver> resolverList,
                           MultiAgentProperties props) {
        this.detector = detector;

        // 按 name() 组装
        Map<String, ConflictResolver> map = new LinkedHashMap<>();
        for (ConflictResolver r : resolverList) {
            ConflictResolver existing = map.put(r.name(), r);
            if (existing != null) {
                throw new IllegalStateException(
                        "冲突解决策略名冲突: " + r.name()
                                + " 同时被 " + existing.getClass().getSimpleName()
                                + " 和 " + r.getClass().getSimpleName() + " 声明");
            }
        }
        this.resolvers = map;
        this.props = props;

        log.info("[ConflictHandler] 初始化：detector={}, resolvers={}, 默认策略={}",
                detector.getClass().getSimpleName(),
                map.keySet(),
                props.getConflict().getStrategy());
    }

    /**
     * 处理冲突——检测 + 解决
     *
     * @return 决议；无冲突返回 {@code noConflict()}
     */
    public ConflictResolution handle(String goal,
                                     AgentRequest parentRequest,
                                     List<AgentResponse> responses) {

        // ① 检测
        long detectStart = System.currentTimeMillis();
        ConflictAnalysis analysis = detector.detect(goal, responses);
        long detectCost = System.currentTimeMillis() - detectStart;

        if (!analysis.hasConflict()) {
            log.debug("[ConflictHandler] 无冲突，跳过解决");
            return ConflictResolution.noConflict();
        }

        log.info("[ConflictHandler] 检测到冲突: severity={}, 有效立场={}, 检测耗时={}ms",
                String.format("%.2f", analysis.severity()),
                analysis.meaningfulOpinionCount(),
                detectCost);

        // ② 选 Resolver
        String strategyName = props.getConflict().getStrategy();
        ConflictResolver resolver = resolvers.get(strategyName);

        if (resolver == null) {
            log.warn("[ConflictHandler] 未知策略 [{}]，无法解决冲突", strategyName);
            return ConflictResolution.unresolved(
                    "NONE", "配置的策略不存在: " + strategyName,
                    analysis.severity(), detectCost);
        }

        // ③ 执行
        ConflictContext ctx = new ConflictContext(goal, parentRequest, responses, analysis);
        ConflictResolution resolution = resolver.resolve(ctx);

        log.info("[ConflictHandler] 处理完成: strategy={}, resolved={}, 总耗时={}ms",
                resolution.strategyName(),
                resolution.hasConflict() && resolution.resolution() != null,
                detectCost + resolution.costMs());

        return resolution;
    }
}