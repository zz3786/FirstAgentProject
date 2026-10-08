package org.example.core.plan.planner;

import lombok.extern.slf4j.Slf4j;
import org.example.core.plan.config.PlanProperties;
import org.example.core.plan.exception.PlanException;
import org.example.core.plan.model.Plan;
import org.example.core.plan.model.PlanStep;
import org.example.core.toolprofile.ToolProfileResolver;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划校验器（D69 升级版）
 *
 * <h3>D69 改动</h3>
 * <p>
 * 原实现分两层校验：
 * <ol>
 *   <li>业务白名单（{@code app.plan.allowed-tools}）</li>
 *   <li>技术可用集（{@code toolRegistry.listNames()}）</li>
 * </ol>
 * <p>
 * D69 引入 ToolProfile 后，两层合并为<b>一层</b>——
 * 因为 Profile 本身就是"业务允许的 ∩ 技术可用的"。
 * PlanValidator 只需校验"工具在 profile 里"。
 */
@Slf4j
@Component
public class PlanValidator {

    private static final String CONSUMER = "plan-validator";

    private final ToolProfileResolver toolProfileResolver;
    private final PlanProperties props;

    public PlanValidator(ToolProfileResolver toolProfileResolver,
                         PlanProperties props) {
        this.toolProfileResolver = toolProfileResolver;
        this.props = props;
    }

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

        // ③ ★ D69：动态从 profile 取可用工具名
        Set<String> availableToolNames = toolProfileResolver.resolveNamesForConsumer(CONSUMER);

        // ④ 每步校验
        for (PlanStep s : steps) {
            validateStep(s, ids, availableToolNames);
        }

        log.info("✅ 计划校验通过: 共 {} 步，可用工具 {} 个（profile: {}）",
                steps.size(), availableToolNames.size(), CONSUMER);
    }

    private void validateStep(PlanStep s,
                              Set<Integer> allIds,
                              Set<String> availableToolNames) {

        // ① 描述非空 + 长度
        if (s.description() == null || s.description().isBlank()) {
            throw fail("步骤 " + s.id() + " 描述为空");
        }
        if (s.description().length() > props.getMaxDescriptionLength()) {
            throw fail("步骤 " + s.id() + " 描述超长");
        }

        // ② 工具校验（D69：单层——profile 已包含白名单 + 可用性）
        if (s.tool() != null && !s.tool().isBlank()) {
            if (!availableToolNames.contains(s.tool())) {
                throw fail("步骤 " + s.id() + " 的工具不在 profile [" + CONSUMER + "] 允许范围内: "
                        + s.tool() + "（当前允许工具: " + availableToolNames + "）");
            }
        }

        // ③ dependsOn 只允许引用更小的 id
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