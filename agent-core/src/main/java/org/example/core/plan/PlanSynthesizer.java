package org.example.core.plan;

import lombok.extern.slf4j.Slf4j;
import org.example.core.plan.model.PlanExecutionState;
import org.example.core.plan.model.StepResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 结果汇总器——把 N 步执行结果组织成用户可读的最终答复
 */
@Slf4j
@Component
public class PlanSynthesizer {

    private final ChatClient synthesizerClient;

    public PlanSynthesizer(@Qualifier("planSynthesizerClient") ChatClient synthesizerClient) {
        this.synthesizerClient = synthesizerClient;
    }

    public String synthesize(PlanExecutionState state) {
        String execLog = state.getStepResults().stream()
                .map(r -> String.format("【步骤 %d】%s\n状态：%s\n产出：%s%s",
                        r.stepId(), r.description(), r.status(),
                        r.output() == null ? "" : r.output(),
                        r.error() == null ? "" : "\n失败原因：" + r.error()))
                .collect(Collectors.joining("\n\n"));

        String prompt = """
                用户原始需求：%s
                
                执行过程：
                %s
                
                请基于以上执行结果，给用户一个完整、简洁的答复。
                要求：
                1. 直接回答用户需求，不要罗列执行过程
                2. 若某步失败，说明"该项未能完成"并给出可行建议
                3. 用自然语言，不要用"根据步骤X"这种术语
                4. 不超过 500 字
                """.formatted(state.getUserInput(), execLog);

        try {
            return synthesizerClient.prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.error("汇总失败，降级为原始结果拼接", e);
            return fallbackSummary(state);
        }
    }

    /** 降级：直接拼结果 */
    private String fallbackSummary(PlanExecutionState state) {
        StringBuilder sb = new StringBuilder("执行结果：\n");
        for (StepResult r : state.getStepResults()) {
            sb.append("- ").append(r.description()).append("：");
            if ("SUCCESS".equals(r.status())) {
                sb.append(r.output());
            } else {
                sb.append("（失败：").append(r.error()).append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}