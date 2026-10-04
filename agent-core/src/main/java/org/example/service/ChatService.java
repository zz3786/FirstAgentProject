package org.example.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.cache.service.SemanticCacheService;
import org.example.common.utils.TextUtils;
import org.example.memory.ConversationMemoryService;
import org.example.rag.retrieval.config.RecommendationProperties;
import org.example.rag.shared.model.RagFilter;
import org.example.rag.retrieval.model.RecommendedDoc;
import org.example.rag.retrieval.model.RetrievalProfile;
import org.example.rag.retrieval.service.ClarificationService;
import org.example.rag.retrieval.service.HybridSearchService;
import org.example.rag.retrieval.service.RecommendationService;
import org.example.rag.retrieval.service.RetrievalProfileService;
import org.example.rag.retrieval.tools.RetrievalPreferenceTools;
import org.example.tools.*;
import org.example.common.utils.ConversationIdUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 核心业务服务
 * <p>
 * <b>职责</b>：
 * <ol>
 *   <li>承接 Controller 请求，编排"缓存 → 前置检索 → 澄清判定 → LLM 生成"的完整流程</li>
 *   <li>管理工具回调和安全包装</li>
 *   <li>把底层异常转成对用户友好的文本</li>
 * </ol>
 * <p>
 * <b>依赖装配</b>：
 * <ul>
 *   <li>{@code redisChatClient}：主业务 Client，带 10 个 Advisor（记忆 + 压缩 + 偏好 + 兴趣 + RAG + 长期记忆 + 对话历史 + 工具日志 + 对话写入 + 提示词日志）</li>
 *   <li>{@code plainChatClient}：裸 Client，无任何 Advisor，用于简单调用</li>
 *   <li>{@code SemanticCacheService}：语义缓存，避免重复调 LLM</li>
 *   <li>{@code HybridSearchService}：混合检索——D47 前置调用，供澄清判定使用</li>
 *   <li>{@code ClarificationService}：D47 澄清判定——检索不明确时反问用户</li>
 *   <li>{@code ChatMemory}：会话记忆，用于"清空对话"功能</li>
 *   <li>10 个工具类：构造注入，在 {@code @PostConstruct} 里包装</li>
 * </ul>
 * <p>
 * Advisor 装配、Memory 绑定、工具装配都在 {@code ChatClientConfig} 里完成。
 * 本类不关心它们怎么构建，只负责用。
 */
@Slf4j
@Service
public class ChatService {

    // ==================== 注入的 Client 与 Service ====================

    /**
     * 主业务 Client（带 10 个 Advisor）
     * <p>
     * 每次调用会依次经过（由 {@code getOrder()} 决定）：
     * <pre>
     * MessageChatMemoryAdvisor（内置，极小 order）
     *   → CompactingChatMemoryAdvisor(50)
     *   → PreferenceAdvisor(100)
     *   → UserInterestAdvisor(120)
     *   → RagAdvisor(150)
     *   → LongTermMemoryAdvisor(200)
     *   → ConversationRetrievalAdvisor(210)
     *   → ConversationMemoryAdvisor(250)
     *   → DebugPromptAdvisor(LOWEST_PRECEDENCE - 1)
     *   → ToolLoggingAdvisor(LOWEST_PRECEDENCE)
     * </pre>
     */
    private final ChatClient chatClientWithMemory;

    /**
     * 裸 Client（无 Advisor、无记忆、无工具）
     * <p>
     * 用于 syncChat / streamChat 这类"简单、一次性"调用。
     */
    private final ChatClient chatClientWithoutMemory;

    /** 语义缓存——避免重复问题重复调 LLM */
    private final SemanticCacheService semanticCacheService;

    /**对话历史向量库服务**/
    private final ConversationMemoryService conversationMemoryService;   // ★ 新增

    /** 混合检索——D47 前置调用，结果传给 RagAdvisor 避免重复检索 */
    private final HybridSearchService hybridSearchService;

    /** 澄清判定——D47 核心，判断检索是否"模糊" */
    private final ClarificationService clarificationService;

    /** D51 个性化检索 核心，" */
    private final RetrievalProfileService retrievalProfileService;

    /** ★ D52：主动推荐配置 */
    private final RecommendationProperties recommendationProperties;

    /** ★ D52：主动推荐服务 */
    private final RecommendationService recommendationService;

    /**
     * 会话记忆
     * <p>
     * 用于 {@link #clearMemory(String)} 真正清空 {@code CHAT:xxx}——
     * 之前只打日志没清，本次补上。
     */
    private final ChatMemory chatMemory;

    // ==================== 工具回调（运行时构建） ====================

    /**
     * 包装后的工具回调
     * <p>
     * 每个原始 {@code ToolCallback} 都被 {@code SafeToolCallback} 包裹，
     * 实现"超时控制 + 入参出参日志 + 异常兜底"。
     * <p>
     * 不能声明为 {@code final}：要在 {@code @PostConstruct} 中赋值。
     */
    private ToolCallback[] wrappedCallbacks;

    // ==================== 工具类（构造注入） ====================

    /** 计算器：加减乘除 */
    private final CalculatorTools calculatorTools;

    /** 文本分析：字数统计、关键词计数 */
    private final TextAnalysisTools textAnalysisTools;

    /** 订单：按订单号查状态 */
    private final OrderTools orderTools;

    /** 待办：创建/列出待办 */
    private final TodoTools todoTools;

    /** 危险操作：测试异常兜底 */
    private final RiskTools riskTools;

    /** 娱乐：讲笑话、名言 */
    private final EntertainmentTools entertainmentTools;

    /** 用户偏好：保存结构化偏好（city/language 等） */
    private final PreferenceTools preferenceTools;

    /** 长期记忆：保存跨会话的重要事实 */
    private final LongMemoryTools longMemoryTools;

    /** 个性化检索：保存跨会话的重要事实 */
    private final RetrievalPreferenceTools retrievalPreferenceTools;

    /** ★ D53：兴趣标签工具 */
    private final InterestTools interestTools;

    // ==================== 构造函数 ====================

    /**
     * 注入所有依赖
     * <p>
     * Advisor 装配、Memory 绑定、工具装配都在 {@code ChatClientConfig} 里完成，
     * 本类只负责用现成的组件。
     *
     * @param chatClientWithMemory   带记忆的主业务 Client
     * @param chatClientWithoutMemory 裸 Client
     * @param semanticCacheService   语义缓存
     * @param hybridSearchService    混合检索——D47 前置检索
     * @param clarificationService   澄清判定——D47
     * @param chatMemory             会话记忆——用于清空对话
     */
    public ChatService(
            @Qualifier("redisChatClient") ChatClient chatClientWithMemory,
            @Qualifier("plainChatClient") ChatClient chatClientWithoutMemory,
            SemanticCacheService semanticCacheService,
            ConversationMemoryService conversationMemoryService,
            HybridSearchService hybridSearchService,
            ClarificationService clarificationService,
            RetrievalProfileService retrievalProfileService,
            RecommendationProperties recommendationProperties,
            RecommendationService recommendationService,
            @Qualifier("redisChatMemory") ChatMemory chatMemory,
            CalculatorTools calculatorTools,
            TextAnalysisTools textAnalysisTools,
            OrderTools orderTools,
            TodoTools todoTools,
            RiskTools riskTools,
            EntertainmentTools entertainmentTools,
            PreferenceTools preferenceTools,
            LongMemoryTools longMemoryTools,
            RetrievalPreferenceTools retrievalPreferenceTools,
            InterestTools interestTools) {
        this.chatClientWithMemory = chatClientWithMemory;
        this.chatClientWithoutMemory = chatClientWithoutMemory;
        this.semanticCacheService = semanticCacheService;
        this.conversationMemoryService = conversationMemoryService;
        this.hybridSearchService = hybridSearchService;
        this.clarificationService = clarificationService;
        this.retrievalProfileService = retrievalProfileService;
        this.recommendationProperties = recommendationProperties;
        this.recommendationService = recommendationService;
        this.chatMemory = chatMemory;
        this.calculatorTools = calculatorTools;
        this.textAnalysisTools = textAnalysisTools;
        this.orderTools = orderTools;
        this.todoTools = todoTools;
        this.riskTools = riskTools;
        this.entertainmentTools = entertainmentTools;
        this.preferenceTools = preferenceTools;
        this.longMemoryTools = longMemoryTools;
        this.retrievalPreferenceTools = retrievalPreferenceTools;
        this.interestTools = interestTools;
    }

    // ==================== 初始化 ====================

    /**
     * 工具回调初始化
     * <p>
     * 执行时机：{@code @PostConstruct} 在"构造函数执行完成"之后触发。
     * 因为所有工具都是构造注入（final 字段），构造器返回时它们已经有值——
     * 所以此处访问 {@code calculatorTools} 等字段是安全的（不是 null）。
     * <p>
     * 做三件事：
     * <ol>
     *   <li>把 10 个工具对象扫描成原始 ToolCallback</li>
     *   <li>用 SafeToolCallback 逐个包装（超时 / 异常 / 日志）</li>
     *   <li>存为实例字段，后续每次对话以 {@code .toolCallbacks(...)} 传入</li>
     * </ol>
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
                        longMemoryTools,
                        retrievalPreferenceTools,
                        interestTools
                )
                .build()
                .getToolCallbacks();

        // ② 逐个包装成 SafeToolCallback（超时 10s、异常转友好文本、入参出参日志）
        this.wrappedCallbacks = Arrays.stream(rawCallbacks)
                .map(SafeToolCallback::new)
                .toArray(ToolCallback[]::new);

        log.info("工具回调初始化完成，共 {} 个工具", wrappedCallbacks.length);
    }

    // ==================== 简单调用（无记忆、无工具） ====================

    /**
     * 同步调用（无记忆、无工具）
     * <p>
     * 用途：快速测试模型连通性、不需要上下文的一次性问答。
     *
     * @param userInput 用户输入
     * @return 模型完整回复（阻塞直到生成完毕）
     */
    public String syncChat(String userInput) {
        return chatClientWithoutMemory.prompt()
                .user(userInput)
                .call()
                .content();
    }

    /**
     * 流式调用（无记忆、无工具）
     * <p>
     * 用途：演示打字机效果、不需要记忆的一次性对话。
     *
     * @param userInput 用户输入
     * @return 文本流，逐 token 推送
     */
    public Flux<String> streamChat(String userInput) {
        return chatClientWithoutMemory.prompt()
                .user(userInput)
                .stream()
                .content();
    }

    // ==================== 主入口（带记忆 + 工具 + D47 澄清） ====================

    /** 兼容旧调用——无过滤 */
    public Flux<String> streamChatWithMemory(String userInput, String conversationId) {
        return streamChatWithMemory(userInput, conversationId, null);
    }

    /**
     * 流式调用 + 会话记忆 + 工具调用 + D47 澄清（主入口）
     * <p>
     * <b>完整链路</b>：
     * <pre>
     * ┌─ ① 语义缓存查询 ─────────────── 命中则直接返回，跳过后续所有步骤
     * │
     * ├─ ② D47 前置 RAG 检索 ────────── 一次检索，两用：
     * │                                   - 供澄清判定
     * │                                   - 传给 RagAdvisor（避免重复检索）
     * │
     * ├─ ③ D47 澄清判定 ──────────────── 若检索模糊 → 直接返回反问文本，不调 LLM
     * │
     * └─ ④ 调 LLM（Advisor 链自动执行）：
     *      1. MessageChatMemoryAdvisor    读 CHAT，注入历史
     *      2. CompactingChatMemoryAdvisor 检查是否压缩（>100 条触发）
     *      3. PreferenceAdvisor           读 USER_PREF，注入偏好
     *      4. RagAdvisor                  用前置检索结果注入 RAG 资料
     *      5. MemoryRetrievalAdvisor      检索 LTM，注入相关记忆
     *      6. ToolLoggingAdvisor          打请求日志
     *      ──────────── 发模型 ────────────
     *      7. 模型可能返回 tool_call
     *      8. SafeToolCallback 执行工具（超时 / 异常兜底）
     *      9. 工具结果回传模型
     *      10. 模型流式返回最终答案
     *      11. MessageChatMemoryAdvisor 写 CHAT
     *      12. 缓存最终答案
     * </pre>
     *
     * @param userInput      用户输入
     * @param conversationId 会话 ID（格式 "userId:sessionTag"，用于隔离 CHAT）
     * @param ragFilter      过滤条件（null 表示不过滤）
     * @return 文本流；出错时返回一段带 ⚠️ 的友好提示
     */
    public Flux<String> streamChatWithMemory(String userInput,
                                             String conversationId,
                                             RagFilter ragFilter) {
        long startTime = System.currentTimeMillis();

        // ★ 归一化 filter（后续所有分支都用 effectiveFilter）
        RagFilter effectiveFilter = (ragFilter == null) ? RagFilter.empty() : ragFilter;

        // ★ 从 conversationId 提取纯 userId 作为租户 ID
        String fullUserId = ConversationIdUtils.extractFullUserId(conversationId);

        // ★ D53：构造过滤维度 Map
        Map<String, Object> filterDims = buildFilterDims(effectiveFilter);

        // ① 语义缓存查询
        String cachedAnswer = semanticCacheService.lookup(userInput, fullUserId, filterDims);
        if (cachedAnswer != null) {
            log.info("✅ [缓存命中] query=[{}]", TextUtils.truncate(userInput, 30));
            return Flux.just(cachedAnswer);
        }

        // ★ D51：读取检索画像
        RetrievalProfile profile = retrievalProfileService.get(fullUserId);

        // ==================== ② D47 前置 RAG 检索 ====================
        // 提前做一次检索——供澄清判定和 RagAdvisor 共用，避免重复
        // 检索失败时降级为空列表——不阻断主流程
        final List<Document> prefetchedDocs = fetchPrefetchedDocs(userInput, effectiveFilter,profile);

        // ==================== ③ D47 澄清判定 ====================
        // 命中模糊条件 → 直接返回反问文本，不调 LLM
        if (clarificationService.isAmbiguous(userInput, prefetchedDocs)) {
            String clarifyMsg = clarificationService.buildClarification(userInput, prefetchedDocs);
            log.info("🤔 [反问] query=[{}] 耗时={}ms（跳过 LLM）",
                    TextUtils.truncate(userInput, 30),
                    System.currentTimeMillis() - startTime);
            // 注意：反问不存语义缓存——Flux.just 不经过 doOnComplete
            return Flux.just(clarifyMsg);
        }

        // ==================== ④ 正常调 LLM ====================
        StringBuilder fullAnswer = new StringBuilder();
        long[] firstTokenTime = {0};

        Flux<String> mainStream =  chatClientWithMemory.prompt()
                .user(userInput)
                .advisors(a -> {
                    // 会话 ID——MessageChatMemoryAdvisor 用来读写 CHAT
                    a.param(ChatMemory.CONVERSATION_ID, conversationId);

                    // 过滤条件——RagAdvisor 用
                    if (!effectiveFilter.isEmpty()) {
                        a.param("rag_filter", effectiveFilter);
                    }

                    // ★ D47：前置检索结果传给 RagAdvisor，避免重复检索
                    //   即使是空 List 也传——表示"确实没结果"，RagAdvisor 直接跳过
                    a.param("prefetched_docs", prefetchedDocs);
                })
                .toolContext(Map.of("userId", fullUserId))       // ★ 关键——把 userId 传给工具
                .toolCallbacks(wrappedCallbacks)
                .stream()
                .content()
                .doOnNext(chunk -> {
                    fullAnswer.append(chunk);
                    if (firstTokenTime[0] == 0) {
                        firstTokenTime[0] = System.currentTimeMillis();
                        log.info("⏱️ [首Token] query=[{}] TTFT={}ms",
                                TextUtils.truncate(userInput, 30),
                                firstTokenTime[0] - startTime);
                    }
                })
                .doOnComplete(() -> {
                    long totalCost = System.currentTimeMillis() - startTime;
                    log.info("⏱️ [完整响应] query=[{}] 总耗时={}ms",
                            TextUtils.truncate(userInput, 30), totalCost);

                    // 缓存最终答案（反问走不到这里——它在前面 return 了）
                    String answer = fullAnswer.toString();
                    if (!answer.isBlank()) {
                        semanticCacheService.store(userInput, answer, fullUserId, filterDims);
                    }
                })
                .doOnError(e -> log.error("⏱️ [异常] query=[{}]",
                        TextUtils.truncate(userInput, 30), e))
                .onErrorResume(e -> {
                    log.error("流式调用异常", e);
                    return Flux.just("⚠️ " + toFriendlyMessage(e));
                });

        // ★ D52：主流结束后追加推荐块
        //   Flux.defer 保证推荐逻辑延迟到"主流 complete 后"才执行
        return mainStream.concatWith(
                Flux.defer(() -> buildRecommendationFlux(
                        userInput, conversationId, prefetchedDocs, effectiveFilter))
        );
    }




    // ==================== 辅助方法 ====================
    /**
     * 清空某个会话的全部记忆
     * <p>
     * <b>"彻底遗忘"语义</b>：清空 = 用户以为删掉的东西真的消失了。两处一起清：
     * <ol>
     *   <li><b>CHAT:*</b>——当前会话的滚动窗口（短期记忆）</li>
     *   <li><b>conv-mem:*</b>——该会话的所有轮次向量（D50 长期历史）</li>
     * </ol>
     * <p>
     * <b>不动</b>：
     * <ul>
     *   <li>LTM:*（长期记忆）——用户的跨会话事实（"我叫张三"）</li>
     *   <li>USER_PREF:*（用户偏好）——用户的稳定偏好</li>
     *   <li>USER_INTEREST:*（兴趣标签）——跨会话累积，不属于某一次对话</li>
     *   <li>semantic-cache:*（语义缓存）——按用户共享，不清</li>
     * </ul>
     * <p>
     * <b>为什么不清 LTM</b>：
     * 用户"清空对话"针对的是"这次聊天记录"，不是"忘掉我"。
     * 要彻底忘掉，得有独立的功能入口（"注销账号"级别）。
     *
     * @param conversationId 完整会话 ID（"hospital-a:user-alice:c383288e"）
     */
    public void clearMemory(String conversationId) {
        // ① 清 CHAT——短期会话窗口
        try {
            chatMemory.clear(conversationId);
            log.info("✅ 已清空 CHAT 短期记忆: conversationId={}", conversationId);
        } catch (Exception e) {
            log.error("清空 CHAT 失败: conversationId={}", conversationId, e);
        }

        // ② 清 conv-mem——D50 对话历史向量库
        //    失败已在 Service 内部 catch，这里只打日志不抛
        boolean vectorDeleted = conversationMemoryService.deleteByConversationId(conversationId);
        if (vectorDeleted) {
            log.info("✅ 已清空 conv-mem 长期历史: conversationId={}", conversationId);
        } else {
            log.warn("⚠️ conv-mem 未清干净（可能本来就没数据）: conversationId={}",
                    conversationId);
        }
    }

    // ==================== 异常友好化 ====================

    /**
     * 把技术异常转成对用户友好的中文提示
     * <p>
     * <b>设计原则</b>：
     * <ul>
     *   <li>不暴露堆栈、异常类名</li>
     *   <li>告诉用户"发生了什么 + 下一步怎么办"</li>
     *   <li>日志里保留完整堆栈，返回给用户的只有友好文本</li>
     * </ul>
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

    /**
     * 前置检索——带异常兜底
     * <p>
     * <b>为什么抽成方法</b>：
     * try/catch 里分两次赋值 prefetchedDocs，会让它变成"非有效 final"——
     * 后续 lambda 无法引用。
     * 抽成方法后，调用方只做一次赋值——满足"有效 final"。
     *
     * @param query  用户问题
     * @param filter 过滤条件
     * @return 检索结果；失败时返回空列表（不阻断主流程）
     */
    private List<Document> fetchPrefetchedDocs(String query, RagFilter filter, RetrievalProfile profile) {
        try {
            List<Document> docs = hybridSearchService.search(query, filter,profile);
            log.info("🔍 [前置检索] query=[{}] 返回 {} 条",TextUtils.truncate(query, 30), docs.size());
            return docs;
        } catch (Exception e) {
            log.warn("🔍 [前置检索] 失败，降级为空结果", e);
            return List.of();
        }
    }

    /**
     * 构建推荐流——追加在主流末尾
     * <p>
     * <b>为什么用 Flux.defer 包裹</b>：
     * 保证推荐逻辑在**主流 complete 之后**才真正执行——
     * 此时 CHAT 里已经写入本轮对话，能正确取到历史提问。
     * <p>
     * <b>为什么吞异常</b>：
     * 推荐是"锦上添花"——任何失败都降级为空 Flux，
     * 绝不能因为推荐异常影响已经成功的回答。
     */
    private Flux<String> buildRecommendationFlux(String userInput,
                                                 String conversationId,
                                                 List<Document> alreadyShown,
                                                 RagFilter filter) {
        try {
            if (!recommendationProperties.isEnabled()) {
                return Flux.empty();
            }

            // ① 排除已引用的 docId
            Set<String> excludeIds = alreadyShown == null
                    ? Set.of()
                    : alreadyShown.stream()
                    .map(d -> (String) d.getMetadata().get("doc_id"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            // ② 取历史提问（不含当前这轮）
            List<String> historyQueries = extractRecentQueries(
                    conversationId, recommendationProperties.getRecentQueryCount());

            // ③ 检索推荐
            List<RecommendedDoc> recs = recommendationService.recommend(
                    userInput, historyQueries, excludeIds, filter);

            if (recs.isEmpty()) {
                log.info("[D52] 无推荐结果，跳过");
                return Flux.empty();
            }

            // ④ 渲染成文本块（作为 Flux 的单个元素推送）
            String block = recommendationService.render(recs);
            log.info("[D52] 追加 {} 条推荐: userId={}, query=[{}]",
                    recs.size(), ConversationIdUtils.extractFullUserId(conversationId), TextUtils.truncate(userInput, 30));
            return Flux.just(block);

        } catch (Exception e) {
            log.warn("[D52] 推荐失败，降级为空", e);
            return Flux.empty();
        }
    }

    /**
     * 从 CHAT 里取最近 N 条用户消息（不含当前这轮）
     * <p>
     * <b>为什么排除最后一条</b>：
     * 主流 complete 时，MessageChatMemoryAdvisor 已经把本轮的 user 写入 CHAT——
     * 它和传入的 userInput 重复，留在 historyQueries 里会重复检索。
     */
    private List<String> extractRecentQueries(String conversationId, int n) {
        try {
            List<org.springframework.ai.chat.messages.Message> history =
                    chatMemory.get(conversationId);
            if (history == null || history.isEmpty()) {
                return List.of();
            }

            List<String> userMsgs = history.stream()
                    .filter(m -> "USER".equals(m.getMessageType().name()))
                    .map(org.springframework.ai.chat.messages.Message::getText)
                    .filter(Objects::nonNull)
                    .toList();

            // 排除最后一条（当前这轮）
            if (userMsgs.size() <= 1) {
                return List.of();
            }
            List<String> previous = userMsgs.subList(0, userMsgs.size() - 1);

            int from = Math.max(0, previous.size() - n);
            return previous.subList(from, previous.size());

        } catch (Exception e) {
            log.warn("取历史提问失败，降级为空", e);
            return List.of();
        }
    }

    /**
     * ★ D53：把 RagFilter 拆成 Map——交给 SemanticCacheService
     * <p>
     * <b>为什么不在 agent-cache 里直接依赖 RagFilter</b>：
     * agent-cache 不能依赖 agent-rag（会循环）。
     * 由 ChatService（agent-core）负责转换——缓存层不感知业务模型。
     */
    private Map<String, Object> buildFilterDims(RagFilter filter) {
        if (filter == null) {
            return Map.of();
        }
        Map<String, Object> dims = new HashMap<>();
        dims.put("departments", filter.departments());
        dims.put("year_from", filter.yearFrom());
        dims.put("year_to", filter.yearTo());
        dims.put("doc_types", filter.docTypes());
        dims.put("security_level", filter.securityLevelMax());
        dims.put("statuses", filter.statuses());
        return dims;
    }
}