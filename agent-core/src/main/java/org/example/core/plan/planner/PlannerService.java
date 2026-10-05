package org.example.core.plan.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.core.plan.config.PlanProperties;
import org.example.core.plan.exception.PlanException;
import org.example.core.plan.model.Plan;
import org.example.core.plan.model.PlanRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 规划器——让 LLM 输出结构化计划
 * <p>
 * 关键设计：
 * <ul>
 *   <li>用 BeanOutputConverter 生成 JSON Schema，保证输出结构稳定</li>
 *   <li>工具清单从实际注册的 ToolCallback 提取——不手写，避免不一致</li>
 *   <li>prompt 里硬约束步数上限、工具白名单</li>
 * </ul>
 */
@Slf4j
@Service
public class PlannerService {

    private final ChatClient plannerClient;
    private final ToolCallback[] toolCallbacks;
    private final PlanProperties props;
    private final BeanOutputConverter<Plan> converter;

    public PlannerService(
            @Qualifier("plannerClient") ChatClient plannerClient,
            @Qualifier("planToolCallbacks") ToolCallback[] toolCallbacks,
            PlanProperties props) {
        this.plannerClient = plannerClient;
        this.toolCallbacks = toolCallbacks;
        this.props = props;
        this.converter = new BeanOutputConverter<>(Plan.class);
    }

    /**
     * 生成计划
     *
     * @throws PlanException 生成或解析失败
     */
    public Plan plan(PlanRequest request) {
        String prompt = buildPlannerPrompt(request);

        try {
            String raw = plannerClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            log.debug("原始计划输出：\n{}", raw);

            if (raw == null || raw.isBlank()) {
                throw new PlanException(PlanException.Code.PLANNING_FAILED,
                        "模型返回空计划");
            }

            Plan plan = converter.convert(raw);
            if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
                throw new PlanException(PlanException.Code.PLANNING_FAILED,
                        "解析后计划为空");
            }

            log.info("📋 计划生成成功：goal=[{}], 步数={}",
                    plan.goal(), plan.steps().size());
            return plan;

        } catch (PlanException e) {
            throw e;
        } catch (Exception e) {
            throw new PlanException(PlanException.Code.PLANNING_FAILED,
                    "计划生成异常: " + e.getMessage(), null, e);
        }
    }

    /** 构造规划 Prompt */
    private String buildPlannerPrompt(PlanRequest request) {
        String toolCatalog = buildToolCatalog();
        String format = converter.getFormat();

        return """
            ================ 角色 ================
            你是一位资深的 AI Agent 任务规划师，擅长把模糊的用户需求
            拆解为"可执行、可验证、无冗余"的原子任务序列。
            
            你的输出会被程序自动执行——任何格式错误、语义模糊、
            工具幻觉都会导致任务失败。所以你的首要目标是【准确】，其次才是【优雅】。
            
            ================ 可用工具清单 ================
            %s
            
            ================ 规划原则 ================
            
            【原则 1：原子性】
            - 每个步骤必须是"一次操作能完成的事"
            - 标准：该步骤能在 30 秒内执行完，输入输出能用一句话说清楚
            - 反例："分析财报并生成报告"（包含两个动作）
            - 正例："读取财报文档" / "提取关键指标" / "生成报告大纲"
            
            【原则 2：工具精准】
            - 能用纯推理解决的，不要硬套工具（tool 填 null）
            - 需要工具时，工具名必须严格来自上方清单
            - 严禁编造工具名——工具名错了整个任务就崩了
            
            【原则 3：依赖显式】
            - 若步骤 B 需要步骤 A 的输出，B.dependsOn 必须包含 A.id
            - 没有依赖的步骤填空数组 []
            - 严禁循环依赖（B 依赖 A，A 又依赖 B）
            
            【原则 4：步骤经济】
            - 步骤总数 ≤ %d
            - 优先"少而精"，不要"多而碎"
            - 若两个步骤是同一类操作（比如连续读 3 个文档），
              合并为"读取这 3 个文档"而不是拆成 3 步
            
            【原则 5：描述具体】
            - 描述必须包含"动作 + 对象 + 目的"
            - 反例："处理数据"（做什么？）
            - 正例："计算 125 加 456 的结果，用于后续填入待办标题"
            - 长度 ≤ %d 字
            
            ================ 示例 ================
            
            【示例 1：简单计算】
            用户需求："帮我算 125 加 456"
            正确输出：
            {
              "goal": "计算 125 + 456 的结果",
              "steps": [
                {
                  "id": 1,
                  "description": "使用 calculate 工具计算 125 加 456",
                  "tool": "calculate",
                  "dependsOn": [],
                  "expectedOutput": "数字结果 581"
                }
              ]
            }
            
            【示例 2：多步依赖】
            用户需求："算 125+456，把结果存到待办里，标题就叫结果数字"
            正确输出：
            {
              "goal": "计算 125+456，并用结果数字创建待办",
              "steps": [
                {
                  "id": 1,
                  "description": "使用 calculate 计算 125 加 456",
                  "tool": "calculate",
                  "dependsOn": [],
                  "expectedOutput": "581"
                },
                {
                  "id": 2,
                  "description": "用上一步的计算结果作为标题，创建一条待办",
                  "tool": "createTodo",
                  "dependsOn": [1],
                  "expectedOutput": "待办创建成功的确认"
                }
              ]
            }
            
            【示例 3：无需工具】
            用户需求："用一句话解释什么是家庭医生"
            正确输出：
            {
              "goal": "解释家庭医生的概念",
              "steps": [
                {
                  "id": 1,
                  "description": "用通俗中文解释家庭医生的定义和服务范围",
                  "tool": null,
                  "dependsOn": [],
                  "expectedOutput": "一段不超过 100 字的说明"
                }
              ]
            }
            
            【反例 1：步骤太碎（禁止）】
            用户需求："算 125+456"
            错误输出：拆成 3 步（"读取输入"、"执行加法"、"格式化输出"）
            错误原因：加法一次工具调用就能完成，不需要拆
            
            【反例 2：工具幻觉（禁止）】
            用户需求："查一下今天的天气"
            错误输出：使用了名为 "getWeather" 的工具
            错误原因：工具清单里没有 getWeather——遇到这种情况应该用 null 工具，
                     让执行步骤自己用语言回答
            
            ================ 输出格式 ================
            只输出一个合法 JSON 对象，不要任何解释文字，不要 markdown 代码块。
            严格遵循以下 Schema：
            
            %s
            
            ================ 用户需求 ================
            %s
            
            ================ 现在开始规划 ================
            """.formatted(
                toolCatalog,
                props.getMaxSteps(),
                props.getMaxDescriptionLength(),
                format,
                request.userInput());
    }

    /**
     * 构造工具目录——比原来的 name + description 更丰富
     */
    private String buildToolCatalog() {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (ToolCallback tc : toolCallbacks) {
            var def = tc.getToolDefinition();
            sb.append(idx++).append(". **").append(def.name()).append("**\n");
            sb.append("   描述：").append(def.description()).append("\n");
            sb.append("   参数：").append(extractParams(def.inputSchema())).append("\n\n");
        }
        return sb.toString();
    }

    /** 从 JSON Schema 里抽出参数摘要 */
    private String extractParams(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) return "（无）";
        try {
            var node = new ObjectMapper().readTree(inputSchema);
            var props = node.get("properties");
            if (props == null) return "（无）";
            StringBuilder sb = new StringBuilder();
            props.fields().forEachRemaining(e -> {
                String name = e.getKey();
                String type = e.getValue().has("type")
                        ? e.getValue().get("type").asText() : "any";
                String required = node.has("required")
                        && node.get("required").toString().contains("\"" + name + "\"")
                        ? "" : "（可选）";
                sb.append(name).append(":").append(type).append(required).append(" ");
            });
            return sb.toString().trim();
        } catch (Exception e) {
            return "（解析失败）";
        }
    }
}