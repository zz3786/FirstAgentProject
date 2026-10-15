package org.example.multiagent.conflict;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.conflict.model.ConflictAnalysis;
import org.example.multiagent.conflict.model.Opinion;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 冲突检测器（D76）
 *
 * <h3>两级检测</h3>
 * <ol>
 *   <li><b>快速规则</b>——不满足基础条件直接返回"无冲突"，避免无谓的 LLM 调用</li>
 *   <li><b>LLM 细判</b>——从 responses 提取意见 + 判断是否有实质性冲突</li>
 * </ol>
 *
 * <h3>快速规则</h3>
 * <ul>
 *   <li>成功响应 < 2 → 无冲突（信息不足，无法比较）</li>
 *   <li>不同角色 < 2 → 无冲突（同一角色不可能"自己和自己冲突"）</li>
 * </ul>
 * <p>
 * 这两条排除了"只有一个 Worker 成功"或"两个 Worker 都是同一角色"的场景——
 * 它们要么信息不足、要么本质无冲突。
 *
 * <h3>LLM 细判做什么</h3>
 * <p>
 * 一次 LLM 调用完成三件事：
 * <ol>
 *   <li>从每个 Worker 的原文提取"核心立场"</li>
 *   <li>判断这些立场之间是否有"实质性冲突"</li>
 *   <li>评估冲突严重度</li>
 * </ol>
 * <p>
 * 为什么一次而不是三次——成本。一次调用省两轮往返。
 *
 * <h3>为什么"提取立场"和"判断冲突"合并</h3>
 * <p>
 * 分开需要两次 LLM 调用。合并的代价是：
 * <ul>
 *   <li>Prompt 稍长——但仍在合理范围</li>
 *   <li>输出结构稍复杂——但用 schema 约束可控</li>
 * </ul>
 * 收益是"一次调用完成"——延迟减半、成本减半。
 */
@Slf4j
public class ConflictDetector {

    private final ChatClient detectorClient;
    private final BeanOutputConverter<ConflictAnalysis> converter;

    public ConflictDetector(ChatClient detectorClient) {
        this.detectorClient = detectorClient;
        this.converter = new BeanOutputConverter<>(ConflictAnalysis.class);
    }

    /**
     * 检测冲突
     *
     * @param goal      原始目标
     * @param responses 所有 Worker 响应
     * @return 冲突分析；无冲突时返回 {@link ConflictAnalysis#noConflict()}
     */
    public ConflictAnalysis detect(String goal, List<AgentResponse> responses) {
        // ── 快速规则
        if (responses == null || responses.size() < 2) {
            log.debug("[ConflictDetector] 响应数 < 2，跳过检测");
            return ConflictAnalysis.noConflict();
        }

        long successCount = responses.stream().filter(AgentResponse::success).count();
        if (successCount < 2) {
            log.debug("[ConflictDetector] 成功响应 < 2，跳过检测");
            return ConflictAnalysis.noConflict();
        }

        long distinctRoles = responses.stream()
                .filter(AgentResponse::success)
                .map(AgentResponse::role)
                .distinct()
                .count();
        if (distinctRoles < 2) {
            log.debug("[ConflictDetector] 不同角色 < 2，跳过检测");
            return ConflictAnalysis.noConflict();
        }

        // ── LLM 细判
        try {
            String opinionsText = buildOpinionsText(responses);
            String prompt = buildDetectPrompt(goal, opinionsText);

            String raw = detectorClient.prompt().user(prompt).call().content();
            if (raw == null || raw.isBlank()) {
                log.warn("[ConflictDetector] LLM 返回空——视为无冲突");
                return ConflictAnalysis.noConflict();
            }

            ConflictAnalysis analysis = converter.convert(cleanJson(raw));
            if (analysis == null) {
                return ConflictAnalysis.noConflict();
            }

            log.info("[ConflictDetector] 检测完成: hasConflict={}, severity={}, 有效立场={}",
                    analysis.hasConflict(),
                    String.format("%.2f", analysis.severity()),
                    analysis.meaningfulOpinionCount());

            return analysis;

        } catch (Exception e) {
            log.warn("[ConflictDetector] 检测异常，降级为无冲突: {}", e.getMessage());
            return ConflictAnalysis.noConflict();
        }
    }

    // ==================== 内部 ====================

    private String buildOpinionsText(List<AgentResponse> responses) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (AgentResponse resp : responses) {
            sb.append("【专家 ").append(idx++).append("：")
                    .append(resp.role().getDisplayName()).append("】\n");
            if (resp.success()) {
                String text = resp.output();
                if (text != null && text.length() > 1500) {
                    text = text.substring(0, 1500) + "...(截断)";
                }
                sb.append(text).append("\n\n");
            } else {
                sb.append("（未成功：").append(resp.error()).append("）\n\n");
            }
        }
        return sb.toString();
    }

    private String buildDetectPrompt(String goal, String opinionsText) {
        String schema = converter.getFormat();
        return """
                ================ 任务 ================
                你是一位冲突检测专家。你的任务是分析多位领域专家对同一问题的意见，
                判断它们之间是否存在【实质性冲突】。
                
                ================ 原始问题 ================
                %s
                
                ================ 专家意见 ================
                %s
                
                ================ 什么算"实质性冲突" ================
                
                ✅ 算冲突：
                - 对同一事实的判断相反（如"能做" vs "不能做"）
                - 对同一方案的评价矛盾（如"成本低" vs "成本高"）
                - 对风险的估计分歧大（如"低风险" vs "高风险"）
                
                ❌ 不算冲突：
                - 从不同角度补充信息（如财务说成本、法务说合规）—— 这是互补
                - 一个说概要、一个说细节 —— 层级不同
                - 措辞差异但语义一致（如"较高" vs "偏高"）—— 表述差异
                
                ================ 处理要求 ================
                1. 先从每位专家的意见中提取"一句话立场"（stance）
                2. 判断这些立场之间是否有实质性冲突
                3. 评估冲突严重度：
                   - 0.0~0.3  = 基本一致，只有措辞差异
                   - 0.3~0.7  = 部分分歧，各有道理
                   - 0.7~1.0  = 根本对立，无法同时成立
                
                ================ 输出格式 ================
                只输出 JSON，不要解释文字，不要 markdown 代码块。
                Schema：
                %s
                
                ================ 现在开始分析 ================
                """.formatted(goal, opinionsText, schema);
    }

    private String cleanJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("^```[a-zA-Z]*\\s*", "")
                    .replaceAll("\\s*```\\s*$", "");
        }
        return s;
    }
}