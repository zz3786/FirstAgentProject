package org.example.core.plan.planner;

import lombok.extern.slf4j.Slf4j;
import org.example.core.plan.config.PlanProperties;
import org.example.core.plan.exception.PlanException;
import org.example.core.plan.model.Plan;
import org.example.core.plan.model.PlanStep;
import org.example.toolregistry.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划校验器——把 LLM 的"自由输出"变成"可执行计划"
 *
 * <h3>校验项</h3>
 * <ul>
 *   <li>步数范围</li>
 *   <li>id 唯一且连续</li>
 *   <li>工具名在业务白名单 + 技术可用集里</li>
 *   <li>dependsOn 只引用更小的 id（防循环）</li>
 *   <li>描述非空、不超长</li>
 * </ul>
 *
 * <h3>D67 改动</h3>
 * <p>
 * 原实现构造时扫一次 {@code ToolCallback[]}，把工具名快照存在
 * {@code registeredToolNames} 字段里。这带来两个问题：
 * <ol>
 *   <li>D68 动态发现后工具会变——快照永远落后于实际</li>
 *   <li>MCP Server 上线 / 下线工具时，Plan 校验会误判"工具不存在"</li>
 * </ol>
 * <p>
 * 改为构造时注入 {@link ToolRegistry}，每次 {@code validate} 时
 * 动态调用 {@link ToolRegistry#listNames()}——永远反映当前状态。
 *
 * <h3>两层校验语义</h3>
 * <p>
 * 工具校验分两层，缺一不可：
 * <ol>
 *   <li><b>业务白名单</b>（{@code app.plan.allowed-tools}）——
 *       由运维配置，是"业务允许 Plan 使用的工具集"。空表示不启用。</li>
 *   <li><b>技术可用集</b>（{@code toolRegistry.listNames()}）——
 *       运行时实际注册的工具，是"物理上能被调用的工具集"。</li>
 * </ol>
 * 一个工具必须同时通过两层校验，才允许出现在 Plan 里。
 * 只通过白名单但技术不可用 → 会在执行阶段失败，提前拦下；
 * 只技术可用但不在白名单 → 是业务禁止 Plan 调用的（如敏感工具）。
 */
@Slf4j
@Component
public class PlanValidator {

    /** 工具注册中心——动态取可用工具名 */
    private final ToolRegistry toolRegistry;

    /** Plan 配置——含业务白名单、步数上限等 */
    private final PlanProperties props;

    public PlanValidator(ToolRegistry toolRegistry,
                         PlanProperties props) {
        this.toolRegistry = toolRegistry;
        this.props = props;
    }

    /**
     * 校验计划，不合法直接抛异常
     *
     * @throws PlanException VALIDATION_FAILED / TOOL_NOT_ALLOWED
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

        // ③ ★ D67：每次动态取当前可用工具名——支持 D68 动态发现
        Set<String> availableToolNames = toolRegistry.listNames();

        // ④ 每步校验
        for (PlanStep s : steps) {
            validateStep(s, ids, availableToolNames);
        }

        log.info("✅ 计划校验通过: 共 {} 步，可用工具 {} 个",
                steps.size(), availableToolNames.size());
    }

    /**
     * 单步校验
     *
     * @param s                   待校验的步骤
     * @param allIds              所有步骤 id 集合——用于 dependsOn 校验
     * @param availableToolNames  ★ D67 新增：当前注册中心里的工具名
     *                            （从 validate 传入，避免每个 step 都查一次 registry）
     */
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

        // ② 工具校验（两层）
        if (s.tool() != null && !s.tool().isBlank()) {

            // 第 1 层：业务白名单——只要配置了就必须通过
            if (!props.getAllowedTools().isEmpty() && !props.getAllowedTools().contains(s.tool())) {
                throw new PlanException(
                        PlanException.Code.TOOL_NOT_ALLOWED,
                        "步骤 " + s.id() + " 使用了未授权工具: " + s.tool(),
                        "step-" + s.id(), null);
            }

            // 第 2 层：技术可用集——必须真的被注册过
            if (!availableToolNames.contains(s.tool())) {
                throw fail("步骤 " + s.id() + " 的工具不存在或未注册: " + s.tool()
                        + "（当前可用工具: " + availableToolNames + "）");
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