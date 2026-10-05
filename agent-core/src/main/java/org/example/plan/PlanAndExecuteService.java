package org.example.plan;

import lombok.extern.slf4j.Slf4j;
import org.example.plan.config.PlanProperties;
import org.example.plan.exception.PlanException;
import org.example.plan.executor.PlanExecutor;
import org.example.plan.model.*;
import org.example.plan.planner.PlanValidator;
import org.example.plan.planner.PlannerService;
import org.example.plan.store.PlanStateStore;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Plan-and-Execute 门面——对外唯一入口
 * <p>
 * 编排：锁 → 规划 → 校验 → 执行 → 汇总 → 释放锁
 */
@Slf4j
@Service
public class PlanAndExecuteService {

    private final PlannerService planner;
    private final PlanValidator validator;
    private final PlanExecutor executor;
    private final PlanSynthesizer synthesizer;
    private final PlanStateStore stateStore;
    private final PlanProperties props;

    public PlanAndExecuteService(PlannerService planner,
                                 PlanValidator validator,
                                 PlanExecutor executor,
                                 PlanSynthesizer synthesizer,
                                 PlanStateStore stateStore,
                                 PlanProperties props) {
        this.planner = planner;
        this.validator = validator;
        this.executor = executor;
        this.synthesizer = synthesizer;
        this.stateStore = stateStore;
        this.props = props;
    }

    public PlanResult execute(PlanRequest request) {
        if (!props.isEnabled()) {
            throw new PlanException(PlanException.Code.PLANNING_FAILED,
                    "Plan-and-Execute 功能未启用");
        }

        String executionId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        // ① 初始化状态
        PlanExecutionState state = new PlanExecutionState();
        state.setExecutionId(executionId);
        state.setConversationId(request.conversationId());
        state.setFullUserId(request.fullUserId());
        state.setUserInput(request.userInput());
        state.setStartTime(start);
        state.setDeadline(start + props.getTotalTimeoutMs());

        // ② 并发锁
        if (!stateStore.tryLock(executionId)) {
            throw new PlanException(PlanException.Code.STATE_CONFLICT,
                    "该执行已在进行中");
        }

        try {
            // ③ 规划
            state.setStatus(PlanStatus.PLANNING);
            Plan plan = planner.plan(request);
            validator.validate(plan);
            state.setCurrentPlan(plan);
            stateStore.save(state);

            // ④ 执行
            executor.execute(state);

            // ⑤ 汇总
            state.setStatus(PlanStatus.SYNTHESIZING);
            String finalAnswer = synthesizer.synthesize(state);
            state.setFinalAnswer(finalAnswer);

            // ⑥ 最终状态
            if (state.getStatus() == PlanStatus.SYNTHESIZING) {
                state.setStatus(PlanStatus.SUCCESS);
            }
            stateStore.save(state);

            boolean success = state.getStatus() == PlanStatus.SUCCESS;
            return buildResult(state, success, null);

        } catch (PlanException e) {
            log.error("Plan 执行失败: {}", e.getMessage());
            state.setStatus(PlanStatus.FAILED);
            stateStore.save(state);
            return buildResult(state, false, e.getMessage());

        } catch (Exception e) {
            log.error("Plan 执行未知异常", e);
            state.setStatus(PlanStatus.FAILED);
            return buildResult(state, false, "服务异常: " + e.getMessage());

        } finally {
            stateStore.releaseLock(executionId);
        }
    }

    /** 加载已有执行——用于中断恢复（可选） */
    public PlanResult resume(String executionId) {
        PlanExecutionState state = stateStore.load(executionId);
        if (state == null) {
            throw new PlanException(PlanException.Code.STATE_CONFLICT,
                    "执行记录不存在或已过期");
        }
        if (!stateStore.tryLock(executionId)) {
            throw new PlanException(PlanException.Code.STATE_CONFLICT,
                    "该执行已在进行中");
        }

        try {
            if (state.getStatus() == PlanStatus.SUCCESS) {
                return buildResult(state, true, null);
            }
            executor.execute(state);
            String finalAnswer = synthesizer.synthesize(state);
            state.setFinalAnswer(finalAnswer);
            if (state.getStatus() == PlanStatus.SYNTHESIZING) {
                state.setStatus(PlanStatus.SUCCESS);
            }
            stateStore.save(state);
            return buildResult(state, state.getStatus() == PlanStatus.SUCCESS, null);
        } finally {
            stateStore.releaseLock(executionId);
        }
    }

    private PlanResult buildResult(PlanExecutionState state, boolean success, String error) {
        return new PlanResult(
                state.getExecutionId(),
                success,
                state.getStatus(),
                state.getFinalAnswer(),
                state.getCurrentPlan(),
                state.getStepResults(),
                state.getReplanCount(),
                System.currentTimeMillis() - state.getStartTime(),
                error);
    }
}