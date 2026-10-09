package org.example.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D54 审计日志
 * <p>
 * <b>用途</b>：
 * <ul>
 *   <li>登录/登出</li>
 *   <li>跨租户访问尝试（安全告警）</li>
 *   <li>越权部门过滤（安全告警）</li>
 *   <li>敏感操作（清空会话/缓存/删除记忆）</li>
 *   <li>文档变更（入库/删除）</li>
 * </ul>
 * <p>
 * <b>为什么独立 Logger</b>：
 * logger name = "AUDIT"——可在 logback-spring.xml 里单独配置输出到
 * logs/audit.log——便于合规审计，不与业务日志混在一起。
 * <p>
 * <b>日志格式</b>：
 * <pre>
 * [AUDIT][事件类型] key1=value1, key2=value2, ...
 * </pre>
 * 结构化字段——便于 ELK/Loki 解析。
 */
public final class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    private AuditLogger() {}

    // ==================== 认证 ====================

    /**
     * 登录成功
     */
    public static void loginSuccess(String userId, String tenantId) {
        log.info("[AUDIT][LOGIN_OK] userId={}, tenantId={}", userId, tenantId);
    }

    /**
     * 登录失败
     */
    public static void loginFail(String userId, String reason) {
        log.warn("[AUDIT][LOGIN_FAIL] userId={}, reason={}", userId, reason);
    }

    /**
     * 登出
     */
    public static void logout(String userId, String tenantId) {
        log.info("[AUDIT][LOGOUT] userId={}, tenantId={}", userId, tenantId);
    }

    // ==================== 安全 ====================

    /**
     * 跨租户访问尝试——最高级别告警
     *
     * @param currentTenant 当前用户所属租户
     * @param userId        当前用户
     * @param targetTenant  尝试访问的目标租户
     * @param resource      访问的资源描述
     */
    public static void crossTenantAttempt(String currentTenant, String userId,
                                          String targetTenant, String resource) {
        log.warn("[AUDIT][CROSS_TENANT] tenantId={}, userId={}, targetTenant={}, resource={}",
                currentTenant, userId, targetTenant, resource);
    }

    /**
     * 越权部门过滤——中等级别告警
     *
     * @param tenantId  当前租户
     * @param userId    当前用户
     * @param requested 请求的部门
     * @param actual    用户实际部门
     */
    public static void unauthorizedDeptFilter(String tenantId, String userId,
                                              String requested, String actual) {
        log.warn("[AUDIT][DEPT_DENIED] tenantId={}, userId={}, requested={}, actual={}",
                tenantId, userId, requested, actual);
    }

    // ==================== 敏感操作 ====================

    /**
     * 敏感操作——清空会话、缓存、删除记忆等
     *
     * @param tenantId 租户
     * @param userId   操作者
     * @param op       操作类型，如 CLEAR_CONVERSATION / CLEAR_CACHE / DELETE_MEMORY
     * @param detail   操作详情
     */
    public static void sensitiveOp(String tenantId, String userId,
                                   String op, String detail) {
        log.info("[AUDIT][SENSITIVE] op={}, tenantId={}, userId={}, detail={}",
                op, tenantId, userId, detail);
    }

    // ==================== 文档变更 ====================

    /**
     * 文档入库
     *
     * @param tenantId  租户
     * @param userId    操作者（自动入库时为 "system"）
     * @param docId     文档 ID
     * @param source    文件名
     */
    public static void docIngest(String tenantId, String userId,
                                 String docId, String source) {
        log.info("[AUDIT][DOC_INGEST] tenantId={}, userId={}, docId={}, source={}",
                tenantId, userId, docId, source);
    }

    /**
     * 文档删除
     */
    public static void docDelete(String tenantId, String userId,
                                 String docId, String source) {
        log.info("[AUDIT][DOC_DELETE] tenantId={}, userId={}, docId={}, source={}",
                tenantId, userId, docId, source);
    }

    // ==================== 通用 ====================

    /**
     * 通用审计事件——灵活调用
     * <p>
     * 格式：{@code [AUDIT][{event}] key1=value1, key2=value2, ...}
     *
     * @param level 日志级别：INFO / WARN / ERROR
     * @param event 事件类型
     * @param kv    键值对（可变参数，成对出现：key1, value1, key2, value2, ...）
     */
    public static void event(String level, String event, Object... kv) {
        String detail = formatKv(kv);
        switch (level == null ? "INFO" : level.toUpperCase()) {
            case "WARN"  -> log.warn("[AUDIT][{}] {}", event, detail);
            case "ERROR" -> log.error("[AUDIT][{}] {}", event, detail);
            default      -> log.info("[AUDIT][{}] {}", event, detail);
        }
    }

    private static String formatKv(Object... kv) {
        if (kv == null || kv.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (i > 0) sb.append(", ");
            sb.append(kv[i]).append("=").append(kv[i + 1]);
        }
        // 奇数个参数——末尾再补一个
        if (kv.length % 2 == 1) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(kv[kv.length - 1]);
        }
        return sb.toString();
    }


    // ==================== 工具鉴权（D69）====================

    /**
     * 工具鉴权拒绝——记录越权尝试
     *
     * <h3>为什么用 WARN 级别</h3>
     * <p>
     * 越权尝试是安全事件——即使最终没有造成损害，
     * 也应该被安全审计系统捕捉，用于：
     * <ul>
     *   <li>事后追查——"是谁在什么时候试图调用什么工具"</li>
     *   <li>实时告警——配合日志监控系统（如 ELK / Loki）触发告警</li>
     *   <li>行为分析——发现可疑用户（多次尝试不同敏感工具）</li>
     * </ul>
     *
     * <h3>日志格式</h3>
     * <pre>
     * [AUDIT][AUTHZ_DENY] scope=TOOL, tool=riskyOperation, userId=hospital-a:user-alice,
     *                     reason=工具 [riskyOperation] 要求最低密级 4，当前用户密级 2
     * </pre>
     * <p>结构化字段——便于 ELK/Loki 解析和聚合。
     *
     * @param scope      拒绝层次——"TOOL"（工具级）或 "PARAM"（参数级）
     * @param toolName   工具名
     * @param fullUserId 完整用户 ID（tenantId:userId）
     * @param reason     拒绝原因——由授权器生成
     */
    public static void authzDeny(String scope, String toolName,
                                 String fullUserId, String reason) {
        log.warn("[AUDIT][AUTHZ_DENY] scope={}, tool={}, userId={}, reason={}",
                scope, toolName, fullUserId, reason);
    }
}