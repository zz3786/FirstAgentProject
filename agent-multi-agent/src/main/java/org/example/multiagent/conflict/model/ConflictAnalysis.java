package org.example.multiagent.conflict.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 冲突分析结果（D76）
 *
 * <h3>字段</h3>
 * <ul>
 *   <li>{@code hasConflict}——是否存在实质性冲突</li>
 *   <li>{@code severity}——冲突严重度（0~1）：
 *       0=完全一致 / 0.5=部分分歧 / 1=根本对立</li>
 *   <li>{@code summary}——冲突总结（hasConflict=true 时填）</li>
 *   <li>{@code opinions}——从各 Worker 提取的立场列表</li>
 * </ul>
 *
 * <h3>为什么带 opinions 而不只是 hasConflict</h3>
 * <p>
 * {@code ConflictResolver}（无论投票还是仲裁）都需要"结构化立场"作为输入。
 * 让 Detector 一次提取，避免 Resolver 再调一次 LLM——<b>省一次调用</b>。
 *
 * <h3>为什么带 severity 而不只是 boolean</h3>
 * <p>
 * 冲突有"程度"：
 * <ul>
 *   <li>轻微分歧（severity < 0.3）——聚合时提一句"存在细微差异"即可</li>
 *   <li>中等分歧（0.3~0.7）——需要仲裁或投票</li>
 *   <li>严重对立（> 0.7）——必须仲裁，且要在最终答案里明确说明</li>
 * </ul>
 * 用 severity 让上层能做"分级响应"。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConflictAnalysis(
        boolean hasConflict,
        double severity,
        String summary,
        List<Opinion> opinions
) {

    public ConflictAnalysis {
        if (opinions == null) {
            opinions = List.of();
        }
        if (summary == null) {
            summary = "";
        }
    }

    /**
     * 无冲突的快捷构造——用于规则预筛通过时
     */
    public static ConflictAnalysis noConflict() {
        return new ConflictAnalysis(false, 0.0, "", List.of());
    }

    /** 有效立场数量 */
    public long meaningfulOpinionCount() {
        return opinions.stream().filter(Opinion::isMeaningful).count();
    }
}