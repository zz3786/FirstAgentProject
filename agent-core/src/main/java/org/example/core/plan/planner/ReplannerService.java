package org.example.core.plan.planner;

import lombok.extern.slf4j.Slf4j;
import org.example.core.plan.config.PlanProperties;
import org.example.core.plan.exception.PlanException;
import org.example.core.plan.model.Plan;
import org.example.core.plan.model.PlanExecutionState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 重规划器——某步失败后，基于"已完成的结果 + 失败原因"生成新的剩余计划
 * <p>
 * 与 PlannerService 的区别：
 * <ul>
 *   <li>Planner：从零规划</li>
 *   <li>Replanner：保留已完成步骤，只重规划剩余部分</li>
 * </ul>
 */
@Slf4j
@Service
public class ReplannerService {

    private final ChatClient plannerClient;
    private final ToolCallback[] toolCallbacks;
    private final PlanProperties props;
    private final BeanOutputConverter<Plan> converter;

    public ReplannerService(
            @Qualifier("plannerClient") ChatClient plannerClient,
            @Qualifier("planToolCallbacks") ToolCallback[] toolCallbacks,
            PlanProperties props) {
        this.plannerClient = plannerClient;
        this.toolCallbacks = toolCallbacks;
        this.props = props;
        this.converter = new BeanOutputConverter<>(Plan.class);
    }

    /**
     * 基于失败情况重新规划
     * <p>
     * 返回的新计划只包含"剩余步骤"——已完成的步骤不再出现。
     *
     * @param state     当前执行状态
     * @param failedAt  失败的步骤 id
     * @param reason    失败原因
     */
    public Plan replan(PlanExecutionState state, int failedAt, String reason) {
        String prompt = buildReplanPrompt(state, failedAt, reason);

        try {
            String raw = plannerClient.prompt().user(prompt).call().content();
            log.debug("重规划原始输出：\n{}", raw);

            Plan newPlan = converter.convert(raw);
            if (newPlan == null || newPlan.steps() == null || newPlan.steps().isEmpty()) {
                throw new PlanException(PlanException.Code.PLANNING_FAILED,
                        "重规划返回空计划");
            }

            log.info("🔄 重规划成功：新计划 {} 步（原计划剩余 {} 步）",
                    newPlan.steps().size(),
                    state.getCurrentPlan().steps().size() - state.getNextStepIndex());
            return newPlan;

        } catch (PlanException e) {
            throw e;
        } catch (Exception e) {
            throw new PlanException(PlanException.Code.PLANNING_FAILED,
                    "重规划异常: " + e.getMessage(), null, e);
        }
    }

    private String buildReplanPrompt(PlanExecutionState state, int failedAt, String reason) {
        String toolList = Arrays.stream(toolCallbacks)
                .map(tc -> "- " + tc.getToolDefinition().name()
                        + "：" + tc.getToolDefinition().description())
                .collect(Collectors.joining("\n"));

        String completed = state.previousSuccessResults().stream()
                .map(r -> String.format("  步骤 %d [%s]：%s",
                        r.stepId(), r.status(),
                        truncate(r.output(), 200)))
                .collect(Collectors.joining("\n"));

        String format = converter.getFormat();

        return """
                ========== 场景 ==========
                原计划执行到某一步失败了，请重新规划"剩余步骤"。
                
                ========== 原始目标 ==========
                %s
                
                ========== 已完成步骤 ==========
                %s
                
                ========== 失败信息 ==========
                步骤 %d 失败：%s
                
                ========== 可用工具 ==========
                %s
                
                ========== 约束 ==========
                1. 新计划里不要再包含已成功的步骤
                2. 步骤 id 从 1 重新编号
                3. 步骤数不超过 %d
                4. 只输出 JSON
                
                ========== 输出格式 ==========
                %s
                """.formatted(
                state.getCurrentPlan().goal(),
                completed.isBlank() ? "（无）" : completed,
                failedAt, reason,
                toolList,
                props.getMaxSteps(),
                format);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}