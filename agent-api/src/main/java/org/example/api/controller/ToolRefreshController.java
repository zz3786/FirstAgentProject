package org.example.api.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.core.toolbootstrap.ToolRefreshResult;
import org.example.core.toolbootstrap.ToolRefreshService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具刷新接口（D68）
 *
 * <h3>用途</h3>
 * <p>
 * 提供运行时刷新工具清单的 HTTP 入口。典型场景：
 * <ul>
 *   <li>MCP Server 重启后，运维手动调本接口刷新 Agent 侧的工具清单</li>
 *   <li>本地工具类发生热部署（如 dev 环境），刷新后立即生效</li>
 *   <li>排查工具注册问题时，快速触发一次刷新并观察日志</li>
 * </ul>
 *
 * <h3>安全考虑</h3>
 * <p>
 * 生产环境应当对此接口加权限控制——至少要求管理员角色。
 * 当前 D68 阶段未加，D69 RBAC 会统一处理。
 *
 * <h3>幂等性</h3>
 * <p>
 * 本接口是幂等的——多次调用结果一致（最终状态一致）。
 * 并发调用时由 {@code ToolRefreshService} 的单飞锁保证
 * "同一时刻只有一个刷新执行"，其他调用返回
 * {@code skipped=true}。
 */
@Slf4j
@RestController
@RequestMapping("/mcp/tools")
public class ToolRefreshController {

    @Resource
    private ToolRefreshService toolRefreshService;

    /**
     * 手动触发一次工具刷新。
     * <p>
     * 返回示例：
     * <pre>
     * {
     *   "code": 200,
     *   "message": "success",
     *   "data": {
     *     "success": true,
     *     "skipped": false,
     *     "beforeCount": 14,
     *     "afterCount": 14,
     *     "localCount": 12,
     *     "mcpCount": 2,
     *     "costMs": 245,
     *     "error": null
     *   }
     * }
     * </pre>
     */
    @PostMapping("/refresh")
    public ApiResponse<ToolRefreshResult> refresh() {
        log.info("[D68] 收到工具刷新请求");
        ToolRefreshResult result = toolRefreshService.refresh();
        return ApiResponse.ok(result);
    }
}