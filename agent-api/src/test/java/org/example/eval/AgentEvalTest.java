package org.example.eval;


import org.example.memory.LongTermMemoryService;
import org.example.preference.UserPreferenceService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentEvalTest {

    @Autowired
    private org.example.service.ChatService chatService;

    @Autowired
    private UserPreferenceService preferenceService;

    @Autowired
    private LongTermMemoryService memoryService;

    @Autowired
    private StringRedisTemplate redis;

    /** 统计结果 */
    private static int total = 0;
    private static int passed = 0;


    @BeforeEach
    void cleanup() {
//        deleteByPattern("CHAT:eval-*");
//        deleteByPattern("LTM:eval-*");
//        deleteByPattern("USER_PREF:eval-*");
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @AfterEach
    void record(TestInfo info) {
        total++;
        // JUnit 会在断言失败时抛异常，能走到这里说明通过
    }

    @AfterAll
    static void report() {
        System.out.println("\n========== 评估报告 ==========");
        System.out.printf("通过率：%d / %d = %.1f%%%n",
                passed, total, total == 0 ? 0 : passed * 100.0 / total);
        System.out.println("=============================\n");
    }

    /** 辅助：收集流式响应 */
    private String collect(String message, String conversationId) {
        StringBuilder sb = new StringBuilder();
        chatService.streamChatWithMemory(message, conversationId)
                .doOnNext(sb::append)
                .blockLast();
        return sb.toString();
    }

    // ========== 用例 1：工具调用 ==========

    /**
     * 观看打印日志,[工具调用开始] calculate | 入参: {"a": 123, "b": 456, "operation": "add"}
     * 1.我的问题是: 123 加 456 等于多少  就这个模型不会直接计算出结果在返回么?
     * 答案是：模型"能算"，但"不敢保证算对"。有了工具它就会优先用工具
     * 模型为什么不算，直接调工具, LLM 本质是文字接龙——它预测下一个 token，不是真的在做数学运算
     *
     * 2.redis里保存的返回参数为什么看不到模型描述的需要调用calculate的结果字段?
     *  AI解答如下:
     * 【第一轮】你 → 模型
     *    用户消息：123 加 456 等于多少
     *    + 工具列表
     *     ↓
     *    模型返回：tool_call(calculate, {a:123, b:456, op:add})   ← assistant 消息①，content=null
     *
     * 【框架执行工具】
     *    calculate(123, 456, add) → 579
     *
     * 【第二轮】你 → 模型
     *    历史：用户消息 + assistant 的 tool_call + tool 结果 579
     *     ↓
     *    模型返回："123 加 456 等于 579"   ← assistant 消息②，content="123 加 456 等于 579"
     *
     * MessageChatMemoryAdvisor 默认只保存"面向用户可见"的消息——也就是最终回答（assistant 2）。
     * 中间的 assistant 1 和 tool 结果被丢弃了，原因是：
     * assistant 1 的 content 是 null，保存了没意义
     * tool 结果是大段 JSON，保存了会污染对话历史
     *
     * 把cleanup()注释之后我发现 我2次请求 eval-tool-001 的内容追加上了
     *
     *
     */
    @Test
    @Order(1)
    @DisplayName("用例1：工具调用 — 计算器")
    void test1_ToolCall() {
        String reply = collect("帮我算一下 125 加 456 等于多少",
                "eval-tool-001");

        System.out.println("【用例1】返回: " + reply);
        boolean ok = reply.contains("579");
        if (ok) passed++;
        assertTrue(ok, "应包含计算结果 579，实际: " + reply);
    }

    // ========== 用例 2：会话记忆 ==========

    /**
     * 观察了控制台日志:
     *  第二轮AI返回:你叫**小明**！😊 我们刚刚认识，还有什么需要帮忙的吗？
     *  我特地的把第一轮文化去掉返现 返回结果就是: 我目前还不知道你的名字呢。😊 你可以告诉我你的名字，这样我就能记住并称呼你啦
     *  还有一个变化: 2轮请求的时候 大模型会返回下面工具调用,第一轮注释之后,就没有触发工具调用
     *  [工具调用开始] saveLongTermMemory | 入参: {"userId": "xiaoming", "type": "FACT", "content": "用户的名字是小明"}
     */
    @Test
    @Order(2)
    @DisplayName("用例2：会话记忆 — 同一会话多轮")
    void test2_SessionMemory() {
        String cid = "eval-mem-001";

        // 第 1 轮：告诉名字
        collect("我叫小明", cid);

        // 第 2 轮：同一会话追问
        String reply = collect("我叫什么名字", cid);

        collect("我有一个小狗叫豆包", cid);

        System.out.println("【用例2】返回: " + reply);
        boolean ok = reply.contains("小明");
        if (ok) passed++;
        assertTrue(ok, "应记得名字小明，实际: " + reply);
    }

    // ========== 用例 3：长期记忆检索 ==========

    /**
     * 测试这个方法的时候遇到个问题:
     * 1.长期记录并没有被检索,AI解答:  根本原因就一句话：extractKeywords 对中文无效，整句话被当成一个关键词，永远匹配不上
     * extractKeywords 是 LongTermMemoryService里的方法,已被优化,优化之后可以匹配到长期历史记忆,并且历史记忆被加入token
     *
     * 2.我以为长期记录 被匹配上之后 ,也会被保存在CHAT（会话记忆)
     *  AI解答:
     *    LTM 检索命中后，只注入本次请求的 SystemMessage，不写回 CHAT。
     *    为什么：
     *    SystemMessage 不是"对话内容"，不该进对话历史
     *    每轮都重新检索，不存在"丢了"的问题
     *    写进 CHAT 会导致 token 爆炸 + 模型混乱
     *    Spring AI 官方设计就是这样
     */
    @Test
    @Order(3)
    @DisplayName("用例3：长期记忆 — 跨会话检索")
    void test3_LongTermMemory() {
        String userId = "eval-ltm-001";   // 固定，不用 UUID

        memoryService.save(userId, "FACT", "用户上周订单1001退货，客服承诺3天内处理");

        String reply = collect("我那个退货的事怎么样了", userId + ":s2");

        System.out.println("【用例3】返回: " + reply);
        boolean ok = reply.contains("1001") || reply.contains("退货");
        if (ok) passed++;
        assertTrue(ok, "应检索到退货记忆，实际: " + reply);
    }

    // ========== 用例 4：用户偏好注入 ==========

    /**
     * 发现 advisor执行顺序是按照order大小决定的 order 越小越有限执行
     * MessageChatMemoryAdvisor  →  PreferenceAdvisor  →  MemoryRetrievalAdvisor  →  模型调用
     */
    @Test
    @Order(4)
    @DisplayName("用例4：用户偏好 — 自动注入")
    void test4_PreferenceInjection() {
        String userId = "eval-pref-001";   // 固定

        // 手动写入偏好
        preferenceService.save(userId, "city", "合肥");

        // 换新会话，问一个和偏好相关的问题
        String reply = collect("推荐一个周末去处",
                userId + ":s1");

        System.out.println("【用例4】返回: " + reply);
        boolean ok = reply.contains("合肥");
        if (ok) passed++;
        assertTrue(ok, "应基于合肥推荐，实际: " + reply);
    }

    // ========== 用例 5：异常兜底 ==========
    @Test
    @Order(5)
    @DisplayName("用例5：异常兜底 — 工具超时返回友好信息")
    void test5_ErrorFallback() {
        String reply = collect("执行一个超时的危险操作",
                "eval-err-001");

        System.out.println("【用例5】返回: " + reply);
        // 不应包含 Java 异常类名，应是自然语言
        boolean ok = !reply.contains("Exception")
                && !reply.contains("TimeoutException")
                && !reply.contains("at org.");
        if (ok) passed++;
        assertTrue(ok, "不应暴露技术异常，实际: " + reply);
    }
}