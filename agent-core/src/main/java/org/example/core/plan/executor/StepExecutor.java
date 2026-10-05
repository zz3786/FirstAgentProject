package org.example.core.plan.executor;

import lombok.extern.slf4j.Slf4j;
import org.example.core.plan.config.PlanProperties;
import org.example.core.plan.model.PlanExecutionState;
import org.example.core.plan.model.PlanStep;
import org.example.core.plan.model.StepResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 单步执行器
 * <p>
 * 职责：
 * <ul>
 *   <li>超时控制——用 Future.get(timeout)</li>
 *   <li>重试——每步最多 retry 次</li>
 *   <li>上下文注入——把前序步骤结果拼进 prompt</li>
 *   <li>工具调用——带上工具回调，让模型自主决定</li>
 * </ul>
 */
@Slf4j
@Component
public class StepExecutor {

    private final ChatClient executorClient;
    private final ToolCallback[] toolCallbacks;
    private final PlanProperties props;

    /** 超时控制线程池——每步独立提交，便于 cancel */
    private final ExecutorService pool = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "plan-step-exec");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public StepExecutor(
            @Qualifier("planExecutorClient") ChatClient executorClient,
            @Qualifier("planToolCallbacks") ToolCallback[] toolCallbacks,
            PlanProperties props) {
        this.executorClient = executorClient;
        this.toolCallbacks = toolCallbacks;
        this.props = props;
    }

    /**
     * 执行单个步骤——带重试和超时
     */
    public StepResult execute(PlanStep step, PlanExecutionState state) {
        long start = System.currentTimeMillis();
        int attempt = 0;
        String lastError = null;

        while (attempt <= props.getPerStepRetry()) {
            try {
                String output = doExecute(step, state, attempt);
                long cost = System.currentTimeMillis() - start;
                log.info("✅ 步骤 {} 执行成功（第 {} 次尝试，耗时 {}ms）",
                        step.id(), attempt + 1, cost);
                return StepResult.success(step, output, cost, attempt);

            } catch (TimeoutException te) {
                lastError = "执行超时（>" + props.getPerStepTimeoutMs() + "ms）";
                log.warn("⏱️ 步骤 {} 第 {} 次尝试超时", step.id(), attempt + 1);

            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("❌ 步骤 {} 第 {} 次尝试失败: {}",
                        step.id(), attempt + 1, e.getMessage());
            }
            attempt++;
        }

        long cost = System.currentTimeMillis() - start;
        return StepResult.failed(step, lastError, cost, attempt);
    }

    /** 单次执行（含超时控制） */
    private String doExecute(PlanStep step, PlanExecutionState state, int attempt)
            throws Exception {

        String prompt = buildStepPrompt(step, state);

        Future<String> future = pool.submit(() ->
                executorClient.prompt()
                        .user(prompt)
                        .toolCallbacks(toolCallbacks)
                        .toolContext(Map.of(
                                "userId", state.getFullUserId(),
                                "executionId", state.getExecutionId(),
                                "stepId", step.id()))
                        .call()
                        .content()
        );

        try {
            String output = future.get(props.getPerStepTimeoutMs(), TimeUnit.MILLISECONDS);
            if (output == null || output.isBlank()) {
                throw new IllegalStateException("模型返回空输出");
            }
            return output;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    /** 构造单步执行 Prompt——把前序结果拼进去 */
    private String buildStepPrompt(PlanStep step, PlanExecutionState state) {
        StringBuilder ctx = new StringBuilder();
        List<StepResult> previous = state.previousSuccessResults();
        if (!previous.isEmpty()) {
            ctx.append("========== 前序步骤结果 ==========\n");
            for (StepResult r : previous) {
                ctx.append("步骤 ").append(r.stepId()).append("：\n")
                        .append(truncate(r.output(), 800)).append("\n\n");
            }
        }

        return """
                ========== 当前任务 ==========
                步骤 %d：%s
                
                预期产出：%s
                
                %s
                
                ========== 要求 ==========
                1. 只完成"当前任务"，不要做多余的事
                2. 如果需要工具，调用对应工具
                3. 完成后用一段话简要说明你的产出
                4. 不要输出与当前任务无关的内容
                """.formatted(
                step.id(),
                step.description(),
                step.expectedOutput() == null ? "未指定" : step.expectedOutput(),
                ctx);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}