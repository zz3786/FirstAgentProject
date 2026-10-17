package org.example.core.plan.planner;

import lombok.extern.slf4j.Slf4j;
import org.example.core.plan.config.PlanProperties;
import org.example.core.plan.exception.PlanException;
import org.example.core.plan.model.Plan;
import org.example.core.plan.model.PlanExecutionState;
import org.example.core.toolprofile.ToolProfileResolver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 重规划器——某步失败后，基于"已完成的结果 + 失败原因"生成新的剩余计划
 *
 * <h3>D78 修复说明</h3>
 * <p>
 * 本类此前通过 {@code @Qualifier("planToolCallbacks")} 注入启动时快照的工具数组。
 * 但 D68/D69 之后：
 * <ul>
 *   <li>{@code PlanChatClientConfig} 已删除 {@code planToolCallbacks} Bean</li>
 *   <li>工具来源统一由 {@link ToolProfileResolver} 按 consumer 名解析</li>
 *   <li>工具清单支持运行时刷新——静态快照会过期</li>
 * </ul>
 * 因此改为注入 {@link ToolProfileResolver}，每次重规划时动态解析。
 * 与 {@code PlannerService} / {@code StepExecutor} 的取工具方式保持一致。
 *
 * <h3>为什么复用 planner-service 消费方</h3>
 * <p>
 * 重规划与初始规划在"可用工具集"上应完全一致——否则会出现
 * "原计划用到的工具在重规划时被判非法"的诡异问题。
 */
@Slf4j
@Service
public class ReplannerService {

    /** Profile 消费方名——与 app-tool-profiles.yml 的 consumers 段对齐 */
    private static final String CONSUMER = "planner-service";

    private final ChatClient plannerClient;
    private final ToolProfileResolver toolProfileResolver;
    private final PlanProperties props;
    private final BeanOutputConverter<Plan> converter;

    public ReplannerService(
            @Qualifier("plannerClient") ChatClient plannerClient,
            ToolProfileResolver toolProfileResolver,
            PlanProperties props) {
        this.plannerClient = plannerClient;
        this.toolProfileResolver = toolProfileResolver;
        this.props = props;
        this.converter = new BeanOutputConverter<>(Plan.class);
    }

    /**
     * 基于失败情况重新规划
     * <p>返回的新计划只包含"剩余步骤"——已完成的步骤不再出现。
     *
     * @param state    当前执行状态
     * @param failedAt 失败的步骤 id
     * @param reason   失败原因
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
        // ★ D78：从 Profile 动态取工具——不再依赖启动时快照
        ToolCallback[] callbacks = toolProfileResolver.resolveForConsumer(CONSUMER);

        String toolList = Arrays.stream(callbacks)
                .map(tc -> "- " + tc.getToolDefinition().name()
                        + "：" + tc.getToolDefinition().description())
                .collect(Collectors.joining("\n"));

        String completed = state.previousSuccessResults().stream()
                .map(r -> String.format("  步骤 %d [%s]：%s",
                        r.stepId(), r.status(), truncate(r.output(), 200)))
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
                failedAt, reason, toolList, props.getMaxSteps(), format);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}