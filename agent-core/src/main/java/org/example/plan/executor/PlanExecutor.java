package org.example.plan.executor;

import lombok.extern.slf4j.Slf4j;
import org.example.plan.config.PlanProperties;
import org.example.plan.model.*;
import org.example.plan.planner.PlanValidator;
import org.example.plan.planner.ReplannerService;
import org.example.plan.store.PlanStateStore;
import org.springframework.stereotype.Component;

/**
 * 计划执行器——主循环
 * <p>
 * 流程：
 * <pre>
 *   while 还有步骤:
 *     if 超时 → 中止
 *     step = 取下一步
 *     result = stepExecutor.execute(step)
 *     if 成功:
 *       记录；推进
 *     if 失败:
 *       if 可重规划:
 *          newPlan = replanner.replan(...)
 *          state 替换 currentPlan，重置 nextStepIndex=0
 *       else:
 *         中止
 * </pre>
 */
@Slf4j
@Component
public class PlanExecutor {

    private final StepExecutor stepExecutor;
    private final ReplannerService replanner;
    private final PlanValidator validator;
    private final PlanStateStore stateStore;
    private final PlanProperties props;

    public PlanExecutor(StepExecutor stepExecutor,
                        ReplannerService replanner,
                        PlanValidator validator,
                        PlanStateStore stateStore,
                        PlanProperties props) {
        this.stepExecutor = stepExecutor;
        this.replanner = replanner;
        this.validator = validator;
        this.stateStore = stateStore;
        this.props = props;
    }

    /**
     * 执行整个计划——从当前 state 开始
     * <p>
     * 执行完成后 state 会被就地更新（成功/失败/超时）。
     */
    public void execute(PlanExecutionState state) {
        state.setStatus(PlanStatus.EXECUTING);

        while (state.hasNextStep()) {
            // ① 超时检查
            if (state.isTimeout()) {
                state.setStatus(PlanStatus.TIMEOUT);
                log.warn("⏱️ 整体超时，中止执行: executionId={}", state.getExecutionId());
                return;
            }

            PlanStep step = state.nextStep();
            log.info("▶️ 执行步骤 {}/{}: [{}]",
                    state.getNextStepIndex() + 1,
                    state.getCurrentPlan().steps().size(),
                    step.description());

            // ② 执行
            StepResult result = stepExecutor.execute(step, state);
            state.addResult(result);

            // ③ 结果处理
            if ("SUCCESS".equals(result.status())) {
                state.advanceStep();
                persistQuietly(state);
                continue;
            }

            // ④ 失败 → 尝试重规划
            log.warn("步骤 {} 失败: {}", step.id(), result.error());

            if (!state.canReplan(props.getMaxReplans())) {
                state.setStatus(PlanStatus.FAILED);
                log.error("❌ 重规划次数用尽，任务中止: executionId={}",
                        state.getExecutionId());
                return;
            }

            try {
                state.setStatus(PlanStatus.REPLANNING);
                Plan newPlan = replanner.replan(state, step.id(), result.error());
                validator.validate(newPlan);

                state.setCurrentPlan(newPlan);
                state.setNextStepIndex(0);
                state.setReplanCount(state.getReplanCount() + 1);
                state.setStatus(PlanStatus.EXECUTING);
                persistQuietly(state);

            } catch (Exception e) {
                state.setStatus(PlanStatus.FAILED);
                log.error("重规划失败，任务中止", e);
                return;
            }
        }

        // 所有步骤执行完毕
        state.setStatus(PlanStatus.SYNTHESIZING);
    }

    private void persistQuietly(PlanExecutionState state) {
        try {
            stateStore.save(state);
        } catch (Exception e) {
            log.warn("状态持久化失败（不阻断执行）: {}", e.getMessage());
        }
    }
}