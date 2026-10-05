package org.example.core.plan.planner;

import lombok.extern.slf4j.Slf4j;
import org.example.core.plan.config.PlanProperties;
import org.example.core.plan.exception.PlanException;
import org.example.core.plan.model.Plan;
import org.example.core.plan.model.PlanStep;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划校验器——把 LLM 的"自由输出"变成"可执行计划"
 * <p>
 * 校验项：
 * <ul>
 *   <li>步数范围</li>
 *   <li>id 唯一且连续</li>
 *   <li>工具名在白名单里</li>
 *   <li>dependsOn 只引用更小的 id（防循环）</li>
 *   <li>描述非空、不超长</li>
 * </ul>
 */
@Slf4j
@Component
public class PlanValidator {

    private final Set<String> registeredToolNames;
    private final PlanProperties props;

    public PlanValidator(@Qualifier("planToolCallbacks") ToolCallback[] toolCallbacks,
                         PlanProperties props) {
        this.registeredToolNames = java.util.Arrays.stream(toolCallbacks)
                .map(tc -> tc.getToolDefinition().name())
                .collect(java.util.stream.Collectors.toSet());
        this.props = props;
    }

    /**
     * 校验计划，不合法直接抛异常
     *
     * @throws PlanException VALIDATION_FAILED
     */
    public void validate(Plan plan) {
        List<PlanStep> steps = plan.steps();

        // ① 步数范围
        if (steps.size() > props.getMaxSteps()) {
            throw fail("步骤数 " + steps.size() + " 超过上限 " + props.getMaxSteps());
        }

        // ② id 唯一且连续
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            PlanStep s = steps.get(i);
            if (s.id() != i + 1) {
                throw fail("步骤 id 必须从 1 连续递增，第 " + (i + 1) + " 个 id=" + s.id());
            }
            if (!ids.add(s.id())) {
                throw fail("步骤 id 重复: " + s.id());
            }
        }

        // ③ 每步校验
        for (PlanStep s : steps) {
            validateStep(s, ids);
        }

        log.info("✅ 计划校验通过: 共 {} 步", steps.size());
    }

    private void validateStep(PlanStep s, Set<Integer> allIds) {
        // 描述非空 + 长度
        if (s.description() == null || s.description().isBlank()) {
            throw fail("步骤 " + s.id() + " 描述为空");
        }
        if (s.description().length() > props.getMaxDescriptionLength()) {
            throw fail("步骤 " + s.id() + " 描述超长");
        }

        // 工具白名单
        if (s.tool() != null && !s.tool().isBlank()) {
            // 白名单总开关：allowedTools 非空才校验
            if (!props.getAllowedTools().isEmpty()
                    && !props.getAllowedTools().contains(s.tool())) {
                throw new PlanException(PlanException.Code.TOOL_NOT_ALLOWED,
                        "步骤 " + s.id() + " 使用了未授权工具: " + s.tool(),
                        "step-" + s.id(), null);
            }
            // 是否真的注册过
            if (!registeredToolNames.contains(s.tool())) {
                throw fail("步骤 " + s.id() + " 的工具不存在: " + s.tool());
            }
        }

        // dependsOn 只允许引用更小的 id
        if (s.dependsOn() != null) {
            for (Integer depId : s.dependsOn()) {
                if (!allIds.contains(depId)) {
                    throw fail("步骤 " + s.id() + " 依赖的 id " + depId + " 不存在");
                }
                if (depId >= s.id()) {
                    throw fail("步骤 " + s.id() + " 不能依赖未来的步骤 " + depId);
                }
            }
        }
    }

    private PlanException fail(String msg) {
        return new PlanException(PlanException.Code.VALIDATION_FAILED, msg);
    }
}