package org.example.multiagent.conflict.model;

import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;

import java.util.List;

/**
 * 冲突处理上下文（D76）
 *
 * <h3>职责</h3>
 * <p>
 * 打包 Resolver 需要的所有信息——包括原始响应和分析结果。
 *
 * <h3>为什么带 responses 而不只是 analysis</h3>
 * <p>
 * 不同 Resolver 的需求不同：
 * <ul>
 *   <li>{@code MajorityVoteResolver}——只用 analysis.opinions 做聚类投票</li>
 *   <li>{@code ArbiterResolver}——需要原文（responses）才能全面评判——
 *       因为 opinions 是"浓缩版"，可能丢失关键论据</li>
 * </ul>
 * <p>
 * 一次打包全部传入——Resolver 按需取用。
 */
public record ConflictContext(
        String goal,
        AgentRequest parentRequest,
        List<AgentResponse> responses,
        ConflictAnalysis analysis
) {

    public ConflictContext {
        if (responses == null) {
            responses = List.of();
        }
        if (analysis == null) {
            analysis = ConflictAnalysis.noConflict();
        }
    }
}