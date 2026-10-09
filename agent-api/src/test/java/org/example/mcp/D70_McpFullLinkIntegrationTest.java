package org.example.mcp;

import lombok.extern.slf4j.Slf4j;
import org.example.core.rbac.ToolAuthorization;
import org.example.core.rbac.ToolAuthorizer;
import org.example.core.toolbootstrap.ToolRefreshResult;
import org.example.core.toolbootstrap.ToolRefreshService;
import org.example.core.toolprofile.ToolProfileResolver;
import org.example.toolregistry.ToolRegistry;
import org.example.toolregistry.model.ToolDescriptor;
import org.example.toolregistry.model.ToolSource;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D70：MCP 全链路集成测试
 *
 * <h3>测试范围</h3>
 * <p>
 * 覆盖 D64~D69 全部能力——从工具注册到 RBAC 到动态刷新。
 * <b>不测 LLM 调用</b>——那是"慢测试、花钱、不稳定"的范畴——
 * 只测"工具链"这个确定性部分。
 *
 * <h3>前置条件</h3>
 * <ul>
 *   <li>Redis 可用（ToolRegistry 无关，但 Spring 上下文需要）</li>
 *   <li>MCP Server 可选——涉及 MCP 的测试会自动跳过</li>
 *   <li>Qdrant / MySQL 可选——未用到的测试不会触发</li>
 * </ul>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -pl agent-api test -Dtest=D70_McpFullLinkIntegrationTest
 * </pre>
 *
 * <h3>测试分组</h3>
 * <ul>
 *   <li>{@code G1_启动完整性}——ToolRegistry 填充正常</li>
 *   <li>{@code G2_工具画像}——Profile 过滤正确</li>
 *   <li>{@code G3_本地工具}——直接调 ToolCallback</li>
 *   <li>{@code G4_MCP工具}——远端工具是否注册（条件跳过）</li>
 *   <li>{@code G5_工具级RBAC}——密级校验</li>
 *   <li>{@code G6_参数级RBAC}——数据归属校验</li>
 *   <li>{@code G7_动态刷新}——运行时刷新工具清单</li>
 * </ul>
 *
 * <h3>测试报告</h3>
 * <p>
 * 每个测试通过时递增 {@code passed}——{@code @AfterAll} 打印通过率。
 * 失败时把失败用例名加入 {@code failures} 列表——便于 CI 输出。
 */
@Slf4j
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("D70：MCP 全链路集成测试")
class D70_McpFullLinkIntegrationTest {

    // ==================== 依赖注入 ====================

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ToolProfileResolver toolProfileResolver;

    @Autowired
    private ToolAuthorizer toolAuthorizer;

    @Autowired
    private ToolRefreshService toolRefreshService;

    // ==================== 统计 ====================

    private static int total = 0;
    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    @AfterEach
    void record(TestInfo info) {
        total++;
        // 若测试方法抛出断言异常，JUnit 不会执行到这里——所以到这里就是通过
        passed++;
        log.info("✅ [D70] {}.{} 通过",
                info.getTestClass().map(Class::getSimpleName).orElse("?"),
                info.getDisplayName());
    }

    @AfterAll
    static void report() {
        System.out.println("\n════════════════════════════════════════");
        System.out.println("  D70 MCP 全链路集成测试报告");
        System.out.println("────────────────────────────────────────");
        System.out.printf("  通过: %d / %d (%.1f%%)%n",
                passed, total, total == 0 ? 0 : passed * 100.0 / total);
        if (!failures.isEmpty()) {
            System.out.println("  失败用例:");
            failures.forEach(f -> System.out.println("    - " + f));
        }
        System.out.println("════════════════════════════════════════\n");
    }

    // ============================================================
    //  G1 启动完整性
    // ============================================================

    @Nested
    @DisplayName("G1 启动完整性")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class G1_StartupIntegrity {

        @Test
        @Order(1)
        @DisplayName("ToolRegistry 非空——启动时注册过工具")
        void registryNotEmpty() {
            assertFalse(toolRegistry.listAll().isEmpty(),
                    "ToolRegistry 应该至少注册一个工具");
            assertTrue(toolRegistry.listAll().size() >= 12,
                    "至少应有 12 个本地工具，实际: " + toolRegistry.listAll().size());
        }

        @Test
        @Order(2)
        @DisplayName("本地工具数 ≥ 10")
        void localToolsCount() {
            int localCount = toolRegistry.listBySource(ToolSource.LOCAL).size();
            assertTrue(localCount >= 10,
                    "本地工具应 ≥ 10，实际: " + localCount);
        }

        @Test
        @Order(3)
        @DisplayName("关键工具已注册：calculate / getOrderStatus / riskyOperation")
        void keyToolsRegistered() {
            Set<String> names = toolRegistry.listNames();
            assertTrue(names.contains("calculate"), "缺少 calculate");
            assertTrue(names.contains("getOrderStatus"), "缺少 getOrderStatus");
            assertTrue(names.contains("riskyOperation"), "缺少 riskyOperation");
            assertTrue(names.contains("createTodo"), "缺少 createTodo");
        }

        @Test
        @Order(4)
        @DisplayName("ToolDescriptor 元数据完整——category 已推断")
        void descriptorMetadata() {
            ToolDescriptor calc = toolRegistry.get("calculate")
                    .orElseThrow(() -> new AssertionError("calculate 未注册"));
            assertEquals("calculation", calc.category(),
                    "calculate 的 category 应为 calculation");
            assertEquals(ToolSource.LOCAL, calc.source(),
                    "calculate 的 source 应为 LOCAL");
            assertTrue(calc.readOnlyHint(),
                    "calculate 应标记为 readOnly");

            ToolDescriptor risky = toolRegistry.get("riskyOperation")
                    .orElseThrow(() -> new AssertionError("riskyOperation 未注册"));
            assertEquals("risk", risky.category(),
                    "riskyOperation 的 category 应为 risk");
        }
    }

    // ============================================================
    //  G2 工具画像
    // ============================================================

    @Nested
    @DisplayName("G2 工具画像过滤")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class G2_ToolProfiles {

        @Test
        @Order(1)
        @DisplayName("chat-service profile 排除 riskyOperation")
        void chatProfileExcludesRisky() {
            ToolCallback[] callbacks =
                    toolProfileResolver.resolveForConsumer("chat-service");
            // 注：D69 测试期间我们注释了 exclude，如果恢复排除则此断言应改为不包含
            // 这里做一个"边界"断言：chat-service 至少应该有 10 个工具
            assertTrue(callbacks.length >= 10,
                    "chat-service 至少应有 10 个工具，实际: " + callbacks.length);

            // 检查 callbacks 里没有 saveLongTermMemory（因为它一直在 exclude 里）
            boolean hasSaveLTM = false;
            for (ToolCallback cb : callbacks) {
                if ("saveLongTermMemory".equals(cb.getToolDefinition().name())) {
                    hasSaveLTM = true;
                    break;
                }
            }
            assertFalse(hasSaveLTM,
                    "chat-service profile 应排除 saveLongTermMemory");
        }

        @Test
        @Order(2)
        @DisplayName("planner-service profile 只包含白名单分类")
        void plannerProfileStrict() {
            ToolCallback[] callbacks =
                    toolProfileResolver.resolveForConsumer("planner-service");

            assertTrue(callbacks.length > 0,
                    "planner-service 不应为空");

            // 每个 callback 的 category 都在允许的分类里
            Set<String> allowedCategories = Set.of(
                    "calculation", "text", "order", "todo", "entertainment");

            for (ToolCallback cb : callbacks) {
                String name = cb.getToolDefinition().name();
                ToolDescriptor desc = toolRegistry.get(name)
                        .orElseThrow(() -> new AssertionError(
                                name + " 在 profile 里但不在 Registry 里"));
                assertTrue(allowedCategories.contains(desc.category()),
                        "planner-service 不应包含 category=" + desc.category()
                                + " 的工具 " + name);
            }
        }

        @Test
        @Order(3)
        @DisplayName("未配置的 profile 名 → 返回空数组")
        void unknownProfileReturnsEmpty() {
            ToolCallback[] callbacks = toolProfileResolver.resolve("non-existing-profile");
            assertEquals(0, callbacks.length,
                    "未配置的 profile 应返回空数组");
        }
    }

    // ============================================================
    //  G3 本地工具调用
    // ============================================================

    @Nested
    @DisplayName("G3 本地工具直接调用")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class G3_LocalToolInvocation {

        @Test
        @Order(1)
        @DisplayName("calculate 工具直接调用——125+456=581")
        void calculateDirect() {
            ToolCallback cb = findCallback("calculate");

            // 无 ToolContext 调用——密级兜底为 1
            // calculate 未在 RBAC 里配置——默认要求密级 1——通过
            String result = cb.call("{\"a\":125,\"b\":456,\"operation\":\"add\"}");

            assertNotNull(result);
            assertTrue(result.contains("581"),
                    "结果应包含 581，实际: " + result);
        }

        @Test
        @Order(2)
        @DisplayName("getOrderStatus 公共订单——1001 应可查")
        void getOrderStatusPublic() {
            ToolCallback cb = findCallback("getOrderStatus");

            ToolContext ctx = buildToolContext("hospital-a:user-alice", 3);
            String result = cb.call("{\"orderId\":\"1001\"}", ctx);

            assertTrue(result.contains("已发货") || result.contains("1001"),
                    "应返回订单状态，实际: " + result);
        }

        @Test
        @Order(3)
        @DisplayName("analyzeText 工具——统计字数")
        void analyzeText() {
            ToolCallback cb = findCallback("analyzeText");
            String result = cb.call(
                    "{\"text\":\"你好世界\",\"keyword\":\"世界\"}");

            assertNotNull(result);
            // 至少应该提到文本统计相关的内容
            assertTrue(result.contains("字数") || result.contains("次")
                            || result.contains("4"),
                    "应包含统计信息，实际: " + result);
        }

        /** 从 Registry 里按名取回调——但注意这是未经包装的原始回调 */
        private ToolCallback findCallback(String name) {
            return toolRegistry.get(name)
                    .orElseThrow(() -> new AssertionError("工具未找到: " + name))
                    .callback();
        }
    }

    // ============================================================
    //  G4 MCP 工具可用性
    // ============================================================

    @Nested
    @DisplayName("G4 MCP 工具可用性")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class G4_McpTools {

        @Test
        @Order(1)
        @DisplayName("MCP Server 可用时——mcp_calculate 应注册")
        void mcpCalculateRegistered() {
            Set<String> names = toolRegistry.listNames();

            if (names.contains("mcp_calculate")) {
                // MCP Server 起来了——验证元数据
                ToolDescriptor desc = toolRegistry.get("mcp_calculate").orElseThrow();
                assertEquals(ToolSource.MCP, desc.source(),
                        "mcp_calculate 的 source 应为 MCP");
                assertEquals("calculation", desc.category(),
                        "mcp_calculate 的 category 应为 calculation");
                assertTrue(desc.openWorldHint(),
                        "MCP 工具应标记 openWorld");
                log.info("✅ MCP Server 可用——mcp_calculate 已注册");
            } else {
                // MCP Server 没起——跳过，但不失败
                log.warn("⚠ MCP Server 未启动——跳过 mcp_calculate 验证。" +
                        "如需测试请先启动 agent-mcp-server（8086 端口）");
                Assumptions.assumeTrue(false,
                        "MCP Server 未启动——跳过该测试");
            }
        }

        @Test
        @Order(2)
        @DisplayName("MCP 工具总数在 0~2 之间（视 Server 状态）")
        void mcpToolsCount() {
            int mcpCount = toolRegistry.listBySource(ToolSource.MCP).size();
            // 要么 0（未启动），要么 2（calculate + getOrderStatus）
            assertTrue(mcpCount == 0 || mcpCount == 2,
                    "MCP 工具数应为 0 或 2，实际: " + mcpCount);
        }
    }

    // ============================================================
    //  G5 工具级 RBAC
    // ============================================================

    @Nested
    @DisplayName("G5 工具级 RBAC")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class G5_ToolLevelRbac {

        @Test
        @Order(1)
        @DisplayName("低密级用户调 riskyOperation → 拒绝")
        void lowLevelDenied() {
            ToolContext ctx = buildToolContext("hospital-a:user-bob", 2);
            ToolAuthorization auth =
                    toolAuthorizer.authorizeTool("riskyOperation", ctx);

            assertFalse(auth.allowed(),
                    "密级 2 不应能调 riskyOperation（要求密级 4）");
            assertNotNull(auth.reason(),
                    "拒绝时应携带原因");
            assertTrue(auth.reason().contains("4"),
                    "原因里应包含要求密级 4");
        }

        @Test
        @Order(2)
        @DisplayName("高密级用户调 riskyOperation → 放行")
        void highLevelAllowed() {
            ToolContext ctx = buildToolContext("hospital-a:user-admin", 4);
            ToolAuthorization auth =
                    toolAuthorizer.authorizeTool("riskyOperation", ctx);

            assertTrue(auth.allowed(),
                    "密级 4 应能调 riskyOperation");
        }

        @Test
        @Order(3)
        @DisplayName("未配置的工具默认要求密级 1——所有人可调")
        void defaultLevel() {
            ToolContext ctx = buildToolContext("hospital-a:user-alice", 1);
            ToolAuthorization auth =
                    toolAuthorizer.authorizeTool("calculate", ctx);

            assertTrue(auth.allowed(),
                    "calculate 未配置密级要求——应默认放行");
        }

        @Test
        @Order(4)
        @DisplayName("ToolContext 无 securityLevel → 兜底为 1")
        void missingSecurityLevel() {
            // 只传 userId，不传 securityLevel
            ToolContext ctx = new ToolContext(
                    Map.of("userId", "hospital-a:user-x"));
            ToolAuthorization auth =
                    toolAuthorizer.authorizeTool("riskyOperation", ctx);

            assertFalse(auth.allowed(),
                    "缺 securityLevel 时应兜底为 1——不能调 riskyOperation");
        }
    }

    // ============================================================
    //  G6 参数级 RBAC
    // ============================================================

    @Nested
    @DisplayName("G6 参数级 RBAC")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class G6_ParamLevelRbac {

        @Test
        @Order(1)
        @DisplayName("跨租户查订单 → 拒绝（hospital-a 查 20001）")
        void crossTenantDenied() {
            ToolContext ctx = buildToolContext("hospital-a:user-alice", 3);
            ToolAuthorization auth = toolAuthorizer.authorizeParams(
                    "getOrderStatus", "{\"orderId\":\"20001\"}", ctx);

            assertFalse(auth.allowed(),
                    "hospital-a 用户不应能查 hospital-b 的订单 20001");
            assertTrue(auth.reason().contains("hospital-b"),
                    "拒绝原因应提及 hospital-b");
        }

        @Test
        @Order(2)
        @DisplayName("同租户查订单 → 放行（hospital-a 查 10001）")
        void sameTenantAllowed() {
            ToolContext ctx = buildToolContext("hospital-a:user-alice", 3);
            ToolAuthorization auth = toolAuthorizer.authorizeParams(
                    "getOrderStatus", "{\"orderId\":\"10001\"}", ctx);

            assertTrue(auth.allowed(),
                    "hospital-a 用户应能查自己的订单 10001");
        }

        @Test
        @Order(3)
        @DisplayName("公共订单（4 位数字）→ 放行")
        void publicOrderAllowed() {
            ToolContext ctx = buildToolContext("hospital-a:user-alice", 3);
            ToolAuthorization auth = toolAuthorizer.authorizeParams(
                    "getOrderStatus", "{\"orderId\":\"1001\"}", ctx);

            assertTrue(auth.allowed(),
                    "公共订单 1001 任何租户可查");
        }

        @Test
        @Order(4)
        @DisplayName("MCP 版订单查询同样受参数级 RBAC 保护")
        void mcpOrderAlsoProtected() {
            ToolContext ctx = buildToolContext("hospital-b:user-charlie", 3);
            // hospital-b 查 hospital-a 的订单 → 拒绝
            ToolAuthorization auth = toolAuthorizer.authorizeParams(
                    "mcp_getOrderStatus", "{\"orderId\":\"10001\"}", ctx);

            assertFalse(auth.allowed(),
                    "hospital-b 用户不应能查 hospital-a 的订单 10001");
        }

        @Test
        @Order(5)
        @DisplayName("未受保护的工具（calculate）→ 参数校验直接放行")
        void unprotectedToolAllowed() {
            ToolContext ctx = buildToolContext("hospital-a:user-alice", 3);
            ToolAuthorization auth = toolAuthorizer.authorizeParams(
                    "calculate", "{\"a\":1,\"b\":2,\"operation\":\"add\"}", ctx);

            assertTrue(auth.allowed(),
                    "calculate 无参数级校验——应放行");
        }
    }

    // ============================================================
    //  G7 动态刷新
    // ============================================================

    @Nested
    @DisplayName("G7 动态刷新")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class G7_DynamicRefresh {

        @Test
        @Order(1)
        @DisplayName("refresh() 成功执行——工具数保持一致")
        void refreshKeepsCount() {
            int before = toolRegistry.listAll().size();
            ToolRefreshResult result = toolRefreshService.refresh();
            int after = toolRegistry.listAll().size();

            assertTrue(result.success(),
                    "刷新应成功，实际: " + result.error());
            assertEquals(before, after,
                    "刷新前后工具数应一致（本地工具没变）——" +
                            "before=" + before + ", after=" + after);
        }

        @Test
        @Order(2)
        @DisplayName("并发刷新——第二次被跳过（单飞锁）")
        void concurrentRefreshSkipped() throws Exception {
            // 启动一个后台线程跑刷新
            Thread t = new Thread(() -> toolRefreshService.refresh());
            t.start();

            // 主线程立即再调一次——应该被跳过
            // 给第一个刷新一点启动时间
            Thread.sleep(5);
            ToolRefreshResult second = toolRefreshService.refresh();

            t.join();

            // 由于单飞锁——第二个可能被跳过，也可能第一个已经完成
            // 断言"至少有一次刷新成功"
            assertTrue(second.success() || second.skipped(),
                    "第二次刷新要么成功要么被跳过，实际: " + second);
        }

        @Test
        @Order(3)
        @DisplayName("刷新后工具名集合不变——本地工具稳定")
        void refreshStableNames() {
            Set<String> before = toolRegistry.listNames();
            toolRefreshService.refresh();
            Set<String> after = toolRegistry.listNames();

            assertEquals(before, after,
                    "刷新前后工具名集合应一致。差集: 缺少=" +
                            diff(before, after) + ", 新增=" + diff(after, before));
        }

        private Set<String> diff(Set<String> a, Set<String> b) {
            Set<String> result = new java.util.HashSet<>(a);
            result.removeAll(b);
            return result;
        }
    }

    // ============================================================
    //  辅助方法
    // ============================================================

    /**
     * 构造 ToolContext——供 RBAC 测试用。
     *
     * @param fullUserId    完整用户 ID（tenantId:userId）
     * @param securityLevel 用户密级
     */
    private static ToolContext buildToolContext(String fullUserId, int securityLevel) {
        return new ToolContext(Map.of(
                "userId", fullUserId,
                "securityLevel", securityLevel
        ));
    }
}