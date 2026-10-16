package org.example.api.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.api.utils.SessionUtils;
import org.example.api.utils.TenantRequestUtils;
import org.example.multiagent.MultiAgentFacade;
import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;
import org.example.multiagent.model.SupervisorResult;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * 多 Agent 协作入口（D77 配套）
 *
 * <h3>定位</h3>
 * <p>
 * D71-D77 建了完整的"主管 - Worker"多 Agent 能力，但一直没有 HTTP 入口——
 * 前端无法触达。本类补齐这个入口。
 *
 * <h3>为什么用 @Profile("dev")</h3>
 * <p>
 * 生产环境如果不需要多 Agent 功能——不暴露本接口减少攻击面。
 * 需要时去掉 @Profile 即可。
 *
 * <h3>为什么设计成 POST</h3>
 * <p>
 * 会调用 LLM（多轮）——不是幂等的查询接口，用 POST 更符合语义。
 */
@Slf4j
@RestController
@RequestMapping("/multiagent")
@Profile("dev")
public class MultiAgentController {

    @Resource
    private MultiAgentFacade multiAgentFacade;

    /**
     * 触发一次多 Agent 协作
     *
     * <h3>请求示例</h3>
     * <pre>
     * POST /fap/multiagent/execute
     * {
     *   "input": "我们准备上线一个用户数据分析系统，请从财务、技术、法务三个角度评估"
     * }
     * </pre>
     *
     * <h3>响应</h3>
     * <pre>
     * {
     *   "code": 200,
     *   "data": {
     *     "output": "综合意见...",
     *     "workerCount": 3,
     *     "workerSuccessRate": 1.0,
     *     "costMs": 35000
     *   }
     * }
     * </pre>
     */
    @PostMapping("/execute")
    public ApiResponse<Map<String, Object>> execute(
            @RequestBody Body body,
            HttpServletRequest request) {

        String tenantId = TenantRequestUtils.getTenantId(request);
        String rawUserId = SessionUtils.getUserId(request);
        String fullUserId = tenantId + ":" + rawUserId;

        log.info("[MultiAgentController] 收到请求: user={}, input=[{}]",
                fullUserId, body.input());

        AgentRequest agentRequest = AgentRequest.of(
                AgentRole.SUPERVISOR,
                body.input(),
                fullUserId);

        AgentResponse response = multiAgentFacade.execute(agentRequest);

        // 组装响应——把关键信息暴露出来
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("success", response.success());
        data.put("output", response.output());
        data.put("error", response.error());
        data.put("costMs", response.costMs());

        // 如果有 SupervisorResult——暴露多 Agent 结构信息
        Object sr = response.metadata().get("supervisorResult");
        if (sr instanceof SupervisorResult result) {
            data.put("goal", result.taskGraph().goal());
            data.put("subtaskCount", result.taskGraph().size());
            data.put("workerCount", result.workerResponses().size());
            data.put("workerSuccessRate", result.workerSuccessRate());
            // 每个 Worker 的响应摘要——便于调试
            data.put("workerResponses", result.workerResponses().stream()
                    .map(wr -> Map.of(
                            "role", wr.role().name(),
                            "success", wr.success(),
                            "costMs", wr.costMs(),
                            "outputPreview", truncate(wr.output(), 200)))
                    .toList());
        }

        return ApiResponse.ok(data);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 请求体 */
    public record Body(String input) {}
}