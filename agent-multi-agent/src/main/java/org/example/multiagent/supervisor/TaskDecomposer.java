package org.example.multiagent.supervisor;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.config.MultiAgentProperties;
import org.example.multiagent.exception.AgentException;
import org.example.multiagent.model.AgentRole;
import org.example.multiagent.model.TaskGraph;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 任务拆解器（D72）
 *
 * <h3>职责</h3>
 * <p>
 * 输入：用户的原始问题（一个字符串）
 * 输出：{@link TaskGraph}——包含 N 个<b>独立</b>子任务
 *
 * <h3>与 PlannerService 的关系</h3>
 * <p>
 * 两者结构相似但<b>语义不同</b>：
 * <table>
 *   <tr><th>维度</th><th>PlannerService（Plan-and-Execute）</th><th>TaskDecomposer（Supervisor）</th></tr>
 *   <tr><td>输出</td><td>串行步骤（有依赖）</td><td>并行子任务（无依赖）</td></tr>
 *   <tr><td>执行者</td><td>同一个 LLM 逐步骤完成</td><td>多个 Worker 并行完成</td></tr>
 *   <tr><td>失败处理</td><td>重规划</td><td>降级 / 部分成功</td></tr>
 *   <tr><td>适用场景</td><td>"先 A 后 B 再 C"</td><td>"A、B、C 三个视角"</td></tr>
 * </table>
 *
 * <p>
 * 虽然不复用代码，但<b>借鉴 PlannerService 的三个模式</b>：
 * <ol>
 *   <li>用 {@code BeanOutputConverter} 生成 schema + 解析输出</li>
 *   <li>Prompt 里写明"原子性 / 无依赖 / 数量上限"三原则</li>
 *   <li>用 {@code ToolProfile} 白名单让 LLM 知道"有哪些角色可用"</li>
 * </ol>
 *
 * <h3>为什么用独立的 plannerClient 而不是反射 Worker</h3>
 * <p>
 * 拆解阶段不需要工具、不需要记忆——只需要"规划脑"。
 * 用干净的 Client 反而效果更稳。这也和 Plan 的做法一致。
 *
 * <h3>拆解失败怎么办</h3>
 * <p>
 * 抛 {@link AgentException#DECOMPOSITION_FAILED}——由 SupervisorAgent 捕获后
 * 降级为"单 Agent 直接回答"（用 D71 的 reflectiveChatClient）。
 * <b>不阻断用户请求</b>——这是生产环境的底线。
 */
@Slf4j
@Component
public class TaskDecomposer {

    /** 可用的 Worker 角色（去掉了 SUPERVISOR / ARBITER——它们不直接执行子任务） */
    private static final AgentRole[] WORKER_ROLES = {
            AgentRole.FINANCIAL,
            AgentRole.TECHNICAL,
            AgentRole.LEGAL
    };

    private final ChatClient plannerClient;
    private final MultiAgentProperties props;
    private final BeanOutputConverter<TaskGraph> converter;

    public TaskDecomposer(
            @Qualifier("supervisorPlannerClient") ChatClient plannerClient,
            MultiAgentProperties props) {
        this.plannerClient = plannerClient;
        this.props = props;
        this.converter = new BeanOutputConverter<>(TaskGraph.class);
    }

    /**
     * 把用户问题拆成独立子任务
     *
     * @param userInput 用户原始问题
     * @return 任务图
     * @throws AgentException 拆解失败（LLM 返回空、格式错误等）
     */
    public TaskGraph decompose(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            throw AgentException.of(AgentException.Code.DECOMPOSITION_FAILED,
                    "用户输入为空");
        }

        int maxSubtasks = props.getSupervisor().getMaxSubtasks();

        try {
            String prompt = buildPrompt(userInput, maxSubtasks);

            String raw = plannerClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            log.debug("[TaskDecomposer] LLM 原始输出:\n{}", raw);

            if (raw == null || raw.isBlank()) {
                throw AgentException.of(AgentException.Code.DECOMPOSITION_FAILED,
                        "LLM 返回空计划");
            }

            TaskGraph graph = converter.convert(cleanJson(raw));

            if (graph == null || graph.isEmpty()) {
                throw AgentException.of(AgentException.Code.DECOMPOSITION_FAILED,
                        "解析后任务图为空");
            }

            // 防御：LLM 有时会拆超上限
            if (graph.size() > maxSubtasks) {
                log.warn("[TaskDecomposer] LLM 拆出 {} 个子任务，超上限 {}——截断",
                        graph.size(), maxSubtasks);
                graph = new TaskGraph(graph.goal(),
                        graph.subtasks().subList(0, maxSubtasks));
            }

            // 防御：修 role 为空或非法的子任务（SubTask 紧凑构造器已兜底，这里再记日志）
            for (var st : graph.subtasks()) {
                if (Arrays.stream(WORKER_ROLES).noneMatch(r -> r == st.role())) {
                    log.warn("[TaskDecomposer] 子任务 {} 的 role={} 不是 Worker 角色——降级为 TECHNICAL",
                            st.id(), st.role());
                }
            }

            log.info("[TaskDecomposer] 拆解成功: goal=[{}], 子任务数={}",
                    graph.goal(), graph.size());

            return graph;

        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw AgentException.of(AgentException.Code.DECOMPOSITION_FAILED,
                    null, "拆解异常: " + e.getMessage(), e);
        }
    }

    // ==================== Prompt ====================

    private String buildPrompt(String userInput, int maxSubtasks) {
        String roleList = Arrays.stream(WORKER_ROLES)
                .map(r -> "- " + r.name() + "（" + r.getDisplayName() + "）")
                .collect(Collectors.joining("\n"));

        String schema = converter.getFormat();

        return """
                ================ 角色 ================
                你是一位资深的多智能体任务规划师。你的任务是把用户的
                复杂问题拆解为 N 个【独立】子任务，每个子任务交给一个
                专业 Agent 并行完成。
                
                ================ 可用 Worker ================
                %s
                
                ================ 拆解原则 ================
                
                【原则 1：独立性（最重要）】
                - 每个子任务必须能被独立完成——不需要等其他子任务的结果
                - 严禁出现"A 完成后才能做 B"这种依赖关系
                - 如果用户问题本质上就是串行的（先 A 后 B），
                  说明问题不适合 Supervisor 模式——请返回**单个子任务**，
                  描述完整的任务
                
                【原则 2：原子性】
                - 每个子任务是一次"专业判断"能完成的事
                - 反例："分析这个方案"（太宽泛）
                - 正例："从财务成本角度评估该方案"（聚焦一个维度）
                
                【原则 3：角色匹配】
                - 每个子任务必须指定合适的 role
                - FINANCIAL：涉及金额、成本、订单、报销、税务
                - TECHNICAL：涉及技术选型、架构、代码、性能
                - LEGAL：涉及合同、合规、隐私、知识产权
                - 不确定时选 TECHNICAL
                
                【原则 4：数量控制】
                - 子任务数 ≤ %d
                - 简单问题（单一视角）拆 1 个
                - 复杂问题（多视角）拆 2~3 个
                - 不要为了"显得专业"而过度拆分
                
                ================ 示例 ================
                
                【示例 1：简单问题——只拆 1 个】
                用户问题："1+1 等于几？"
                输出：
                {
                  "goal": "计算 1+1",
                  "subtasks": [
                    {
                      "id": 1,
                      "description": "计算 1+1 的结果",
                      "role": "TECHNICAL",
                      "expectedOutput": "数字 2"
                    }
                  ]
                }
                
                【示例 2：多视角问题——拆 3 个】
                用户问题："我们公司要上一个客户管理系统，请评估可行性"
                输出：
                {
                  "goal": "评估客户管理系统的可行性",
                  "subtasks": [
                    {
                      "id": 1,
                      "description": "从成本角度评估客户管理系统的实施和运维费用",
                      "role": "FINANCIAL",
                      "expectedOutput": "成本估算和投资回报分析"
                    },
                    {
                      "id": 2,
                      "description": "从技术选型和架构角度评估系统的可行性",
                      "role": "TECHNICAL",
                      "expectedOutput": "技术栈建议和实施难度评估"
                    },
                    {
                      "id": 3,
                      "description": "评估客户数据采集和存储的合规性要求",
                      "role": "LEGAL",
                      "expectedOutput": "数据合规风险清单"
                    }
                  ]
                }
                
                【反例：串行依赖（禁止）】
                用户问题："先查订单，再根据订单状态决定是否退款"
                错误输出：拆成 "查订单" 和 "根据状态退款" 两个子任务——有依赖
                正确做法：整个问题作为一个子任务，交给 FINANCIAL Worker
                
                ================ 输出格式 ================
                只输出一个合法 JSON 对象，不要任何解释文字，不要 markdown 代码块。
                Schema：
                %s
                
                ================ 用户问题 ================
                %s
                
                ================ 现在开始拆解 ================
                """.formatted(roleList, maxSubtasks, schema, userInput);
    }

    /**
     * 清理 LLM 输出中可能存在的 markdown 代码块包裹
     */
    private String cleanJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("^```[a-zA-Z]*\\s*", "")
                    .replaceAll("\\s*```\\s*$", "");
        }
        return s;
    }
}