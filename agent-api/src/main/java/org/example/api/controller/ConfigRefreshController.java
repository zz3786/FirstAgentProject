package org.example.api.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.common.audit.AuditLogger;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * 配置热刷新入口
 *
 * <h3>用途</h3>
 * <p>
 * 提供运行时刷新配置的 HTTP 入口。调用后：
 * <ol>
 *   <li>Spring Cloud Context 重新读取所有 {@code spring.config.import} 的配置文件</li>
 *   <li>所有标注 {@code @RefreshScope} 的 Bean 被销毁并用新配置重建</li>
 *   <li>返回本次发生变化的配置项 key 集合</li>
 * </ol>
 *
 * <h3>典型场景</h3>
 * <ul>
 *   <li>运维改完 {@code app-rbac.yml}——调本接口让 RBAC 规则即时生效</li>
 *   <li>运维改完 {@code app-tool-profiles.yml}——调本接口让工具画像即时生效</li>
 *   <li>本地开发——配合 DevTools 或手动调用，避免重启</li>
 * </ul>
 *
 * <h3>与 /actuator/refresh 的关系</h3>
 * <p>
 * Spring Cloud Context 自带 {@code POST /actuator/refresh} 端点——
 * 本类只是包一层，好处是：
 * <ul>
 *   <li>保持项目 {@link ApiResponse} 统一响应体</li>
 *   <li>接入 {@link AuditLogger}——配置变更可追溯</li>
 *   <li>未来加权限控制（D69 RBAC 的 Controller 层扩展）</li>
 *   <li>路径风格统一（{@code /fap/config/refresh} 而不是 {@code /fap/actuator/refresh}）</li>
 * </ul>
 * <p>
 * 若不需要这些包装，可以直接用 {@code /actuator/refresh}——
 * 但需要保证 {@code management.endpoints.web.exposure.include} 里包含 {@code refresh}。
 *
 * <h3>安全提醒</h3>
 * <p>
 * <b>本接口应当受权限保护</b>——只有管理员能调。
 * 当前未加鉴权（D69 的 RBAC 只覆盖工具调用，不覆盖 Controller）。
 * 生产环境建议：
 * <ul>
 *   <li>加 {@code @PreAuthorize("hasRole('ADMIN')")} 或类似的接口级鉴权</li>
 *   <li>或通过 Nginx / Gateway 层做 IP 白名单</li>
 *   <li>或将本接口部署到内网专用端口</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/config")
public class ConfigRefreshController {

    /**
     * Spring Cloud Context 提供的刷新器。
     * <p>
     * {@code refresh()} 方法：
     * <ul>
     *   <li>重新加载所有配置源</li>
     *   <li>发布 {@code EnvironmentChangeEvent} 事件</li>
     *   <li>销毁并重建所有 {@code @RefreshScope} Bean</li>
     *   <li>返回发生变化的配置项 key 集合（无变化时为空集）</li>
     * </ul>
     */
    @Resource
    private ContextRefresher contextRefresher;

    /**
     * 触发一次配置刷新。
     *
     * <h3>返回示例</h3>
     * <pre>
     * {
     *   "code": 200,
     *   "message": "success",
     *   "data": {
     *     "changedKeys": ["app.rbac.tool-requirements.riskyOperation.min-security-level"],
     *     "changedCount": 1,
     *     "costMs": 125
     *   }
     * }
     * </pre>
     *
     * <h3>注意事项</h3>
     * <ul>
     *   <li>只有加了 {@code @RefreshScope} 的 Bean 才会被重建——
     *       没加的 Bean 属性不会更新</li>
     *   <li>若注入方在构造函数里读了属性值并缓存到字段——
     *       即使属性本身是 {@code @RefreshScope}，
     *       缓存也不会变（需要注入方自己也用代理或每次读属性）</li>
     *   <li>配置源如果是 jar 内 classpath 资源，
     *       运行时修改的应该是"jar 外的覆盖文件"或"外部配置目录"——
     *       直接改 jar 内的 yml 无效（因为 jar 不可写）</li>
     * </ul>
     */
    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh() {
        long start = System.currentTimeMillis();
        log.info("[Config] 收到配置刷新请求");

        try {
            // ★ 核心调用——触发刷新
            Set<String> changedKeys = contextRefresher.refresh();

            long cost = System.currentTimeMillis() - start;

            // 审计日志——记录本次刷新
            AuditLogger.sensitiveOp(
                    "system",                       // tenantId——配置刷新是系统级操作
                    "system",                       // userId——同上
                    "CONFIG_REFRESH",
                    "changedCount=" + changedKeys.size()
                            + ", keys=" + changedKeys
                            + ", costMs=" + cost
            );

            if (changedKeys.isEmpty()) {
                log.info("[Config] 刷新完成——无配置项发生变化，耗时 {}ms", cost);
            } else {
                log.info("[Config] 刷新完成——变化 {} 项: {}，耗时 {}ms",
                        changedKeys.size(), changedKeys, cost);
            }

            return ApiResponse.ok(Map.of(
                    "changedKeys", changedKeys,
                    "changedCount", changedKeys.size(),
                    "costMs", cost
            ));

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[Config] 刷新失败，耗时 {}ms", cost, e);

            // 审计失败事件
            AuditLogger.sensitiveOp(
                    "system",
                    "system",
                    "CONFIG_REFRESH_FAILED",
                    "error=" + e.getMessage()
            );

            return ApiResponse.fail(500, "配置刷新失败: " + e.getMessage());
        }
    }
}