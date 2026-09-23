package org.example.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.tools.SafeToolCallback;
import org.example.tools.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.Arrays;

/**
 * Agent 核心业务服务
 * <p>
 * 职责：
 * 1. 承接 Controller 的请求，调用 ChatClient 完成对话
 * 2. 管理工具回调和安全包装
 * 3. 把底层异常转成对用户友好的文本
 * <p>
 * 依赖装配：
 * - {@code redisChatClient}：主业务 Client，带记忆 + 压缩 + 偏好 + 检索 + 日志
 * - {@code plainChatClient}：裸 Client，无任何 Advisor，用于简单调用
 * - 8 个工具类：通过 @Resource 注入，在 @PostConstruct 里包装
 * <p>
 * 装配细节全部在 {@code ChatClientConfig} 里，本类只负责业务。
 */
@Slf4j
@Service
public class ChatService {

    // ==================== 注入的 Client ====================

    /**
     * 主业务 Client（带 5 个 Advisor）
     * <p>
     * 每次调用会经过：
     * MessageChatMemoryAdvisor → CompactingChatMemoryAdvisor
     *   → PreferenceAdvisor → MemoryRetrievalAdvisor → ToolLoggingAdvisor
     */
    private final ChatClient chatClientWithMemory;

    /**
     * 裸 Client（无 Advisor、无记忆、无工具）
     * <p>
     * 用于 syncChat / streamChat 这类"简单、一次性"调用。
     */
    private final ChatClient chatClientWithoutMemory;

    // ==================== 工具回调（运行时构建） ====================

    /**
     * 包装后的工具回调
     * <p>
     * 每个原始 ToolCallback 都被 SafeToolCallback 包裹，
     * 实现「超时控制 + 入参出参日志 + 异常兜底」。
     * <p>
     * 不能声明为 final：因为要在 @PostConstruct 中赋值。
     */
    private ToolCallback[] wrappedCallbacks;

    // ==================== 工具类（@Resource 字段注入） ====================

    /** 计算器：加减乘除 */
    @Resource private CalculatorTools calculatorTools;

    /** 文本分析：字数统计、关键词计数 */
    @Resource private TextAnalysisTools textAnalysisTools;

    /** 订单：按订单号查状态 */
    @Resource private OrderTools orderTools;

    /** 待办：创建/列出待办 */
    @Resource private TodoTools todoTools;

    /** 危险操作：测试异常兜底 */
    @Resource private RiskTools riskTools;

    /** 娱乐：讲笑话、名言 */
    @Resource private EntertainmentTools entertainmentTools;

    /** 用户偏好：保存结构化偏好（city/language 等） */
    @Resource private PreferenceTools preferenceTools;

    /** 长期记忆：保存跨会话的重要事实 */
    @Resource private MemoryTools memoryTools;

    // ==================== 构造函数 ====================

    /**
     * 只注入现成的两个 ChatClient。
     * <p>
     * Advisor 装配、Memory 绑定、工具装配都在 {@code ChatClientConfig} 里完成。
     * 本类不关心 ChatClient 是怎么构建的，只负责用。
     *
     * @param chatClientWithMemory  带记忆的主业务 Client
     * @param chatClientWithoutMemory 裸 Client
     */
    public ChatService(
            @Qualifier("redisChatClient") ChatClient chatClientWithMemory,
            @Qualifier("plainChatClient") ChatClient chatClientWithoutMemory) {
        this.chatClientWithMemory = chatClientWithMemory;
        this.chatClientWithoutMemory = chatClientWithoutMemory;
    }

    // ==================== 初始化 ====================

    /**
     * 工具回调初始化
     * <p>
     * 执行时机：{@code @PostConstruct} 在「构造函数执行完成 + @Resource 字段注入完成」之后触发。
     * 所以此处访问 calculatorTools 等字段是安全的（不是 null）。
     * <p>
     * 做三件事：
     * 1. 把 8 个工具对象扫描成原始 ToolCallback
     * 2. 用 SafeToolCallback 逐个包装（超时/异常/日志）
     * 3. 存为实例字段，后续每次对话以 .toolCallbacks(...) 传入
     */
    @PostConstruct
    public void initToolCallbacks() {
        // ① 扫描所有 @Tool 注解的方法，生成原始回调
        ToolCallback[] rawCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(
                        calculatorTools,
                        textAnalysisTools,
                        orderTools,
                        todoTools,
                        riskTools,
                        entertainmentTools,
                        preferenceTools,
                        memoryTools
                )
                .build()
                .getToolCallbacks();

        // ② 逐个包装成 SafeToolCallback（超时 10s、异常转友好文本、入参出参日志）
        this.wrappedCallbacks = Arrays.stream(rawCallbacks)
                .map(SafeToolCallback::new)
                .toArray(ToolCallback[]::new);

        log.info("工具回调初始化完成，共 {} 个工具", wrappedCallbacks.length);
    }

    // ==================== 业务方法 ====================

    /**
     * 同步调用（无记忆、无工具）
     * <p>
     * 用途：快速测试模型连通性、不需要上下文的一次性问答。
     * 返回：完整回复字符串（阻塞直到模型生成完毕）。
     *
     * @param userInput 用户输入
     * @return 模型完整回复
     */
    public String syncChat(String userInput) {
        return chatClientWithoutMemory.prompt()
                .user(userInput)
                .call()         // 同步阻塞调用
                .content();     // 提取文本内容
    }

    /**
     * 流式调用（无记忆、无工具）
     * <p>
     * 用途：演示打字机效果、不需要记忆的一次性对话。
     * 返回：Flux&lt;String&gt; 逐 token 推送。
     *
     * @param userInput 用户输入
     * @return 文本流
     */
    public Flux<String> streamChat(String userInput) {
        return chatClientWithoutMemory.prompt()
                .user(userInput)
                .stream()       // 流式调用
                .content();     // 返回 Flux<String>
    }

    /**
     * 流式调用 + 会话记忆 + 工具调用（主入口）
     * <p>
     * 一次调用经过的完整链路：
     * <pre>
     * 1. MessageChatMemoryAdvisor    读 CHAT，注入历史
     * 2. CompactingChatMemoryAdvisor 检查是否压缩（>100 条触发）
     * 3. PreferenceAdvisor           读 USER_PREF，注入偏好
     * 4. MemoryRetrievalAdvisor      检索 LTM，注入相关记忆
     * 5. ToolLoggingAdvisor          打请求日志
     * ──────────── 发模型 ────────────
     * 6. 模型可能返回 tool_call
     * 7. SafeToolCallback 执行工具（超时/异常兜底）
     * 8. 工具结果回传模型
     * 9. 模型流式返回最终答案
     * 10. MessageChatMemoryAdvisor 写 CHAT
     * </pre>
     *
     * @param userInput      用户输入
     * @param conversationId 会话 ID（=userId，用于隔离 CHAT）
     * @return 文本流；出错时返回一段带 ⚠️ 的友好提示
     */
    public Flux<String> streamChatWithMemory(String userInput, String conversationId) {
        return chatClientWithMemory.prompt()
                .user(userInput)
                // 指定会话 ID：让 MessageChatMemoryAdvisor 知道读写哪个 CHAT
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                // 传入包装后的工具（本类在 @PostConstruct 里构建好）
                .toolCallbacks(wrappedCallbacks)
                .stream()
                .content()
                // 流式调用的异常兜底：把技术异常转成用户能看懂的文本
                .onErrorResume(e -> {
                    log.error("流式调用异常", e);
                    String friendly = toFriendlyMessage(e);
                    // 通过 SSE 推一条带 ⚠️ 前缀的提示，前端直接渲染
                    return Flux.just("⚠️ " + friendly);
                });
    }

    /**
     * 清空某个会话的对话历史
     * <p>
     * 用途：chat.html 的"清空对话"按钮。
     * 只是清空 CHAT:*（会话记忆），不动 LTM 和 USER_PREF。
     *
     * @param conversationId 会话 ID
     */
    public void clearMemory(String conversationId) {
        try {
            // 通过 ChatMemory 接口清空（底层委托给 RedisChatMemoryRepository）
            // 注意：这里需要注入 ChatMemory，见下方可选方案
            log.info("清空会话历史: conversationId={}", conversationId);
            // chatMemory.clear(conversationId);   // 如果要真正清空，注入 ChatMemory 后启用
        } catch (Exception e) {
            log.error("清空会话失败: conversationId={}", conversationId, e);
        }
    }

    // ==================== 异常友好化 ====================

    /**
     * 把技术异常转成对用户友好的中文提示。
     * <p>
     * 设计原则：
     * - 不暴露堆栈、异常类名
     * - 告诉用户"发生了什么 + 下一步怎么办"
     * - 日志里保留完整堆栈，返回给用户的只有友好文本
     *
     * @param e 原始异常
     * @return 用户可读的提示
     */
    private String toFriendlyMessage(Throwable e) {
        // ① AI 服务调用异常（DeepSeek 400/401/429 等）
        if (e instanceof WebClientResponseException w) {
            int s = w.getStatusCode().value();
            if (s == 401) {
                return "AI 服务认证失败，请联系管理员";
            }
            if (s == 429) {
                return "请求太频繁，请稍后重试";
            }
            return "AI 服务暂时不可用，请稍后重试";
        }

        // ② Redis 连接异常（记忆服务不可用）
        if (e instanceof RedisConnectionFailureException) {
            return "记忆服务暂时不可用，请稍后重试";
        }

        // ③ 业务状态异常（比如未登录）
        if (e instanceof IllegalStateException) {
            return e.getMessage() == null ? "请先登录" : e.getMessage();
        }

        // ④ 兜底：不暴露任何技术细节
        return "服务出了点问题，请稍后重试";
    }
}