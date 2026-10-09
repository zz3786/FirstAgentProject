package org.example.core.chat.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.cache.service.SemanticCacheService;
import org.example.common.utils.ConversationIdUtils;
import org.example.common.utils.TextUtils;
import org.example.core.rbac.ToolAuthorizer;
import org.example.core.rbac.wrapper.AuthzToolCallback;
import org.example.core.toolprofile.ToolProfileResolver;
import org.example.core.tools.SafeToolCallbackFactory;
import org.example.memory.ConversationMemoryService;
import org.example.rag.retrieval.config.RecommendationProperties;
import org.example.rag.retrieval.model.RecommendedDoc;
import org.example.rag.retrieval.model.RetrievalProfile;
import org.example.rag.retrieval.service.ClarificationService;
import org.example.rag.retrieval.service.HybridSearchService;
import org.example.rag.retrieval.service.RecommendationService;
import org.example.rag.retrieval.service.RetrievalProfileService;
import org.example.rag.shared.model.RagFilter;
import org.example.toolregistry.ToolRefreshListener;
import org.example.toolregistry.ToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 核心业务服务
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>承接 Controller 请求，编排"缓存 → 前置检索 → 澄清判定 → LLM 生成"的完整流程</li>
 *   <li>管理工具回调和安全包装</li>
 *   <li>把底层异常转成对用户友好的文本</li>
 * </ol>
 *
 * <h3>D68 / D69 后的工具装配链</h3>
 * <pre>
 *   ToolRegistry（全量工具，D67）
 *       ↓
 *   ToolProfileResolver（按 chat-service 画像过滤）
 *       ↓
 *   SafeToolCallback（重试/超时/日志/异常兜底，D66）
 *       ↓
 *   AuthzToolCallback（工具级 + 参数级鉴权，D69）
 *       ↓
 *   wrappedCallbacks → 传给 ChatClient
 * </pre>
 * <p>
 * <b>注意执行顺序</b>：鉴权在最外层——失败不消耗重试/超时预算。
 */
@Slf4j
@Service
public class ChatService implements ToolRefreshListener {

    // ==================== 注入的 Client 与 Service ====================

    /** 主业务 Client（带 Advisor 链） */
    private final ChatClient chatClientWithMemory;

    /** 裸 Client（无 Advisor、无记忆、无工具） */
    private final ChatClient chatClientWithoutMemory;

    /** 语义缓存 */
    private final SemanticCacheService semanticCacheService;

    /** 对话历史向量库服务 */
    private final ConversationMemoryService conversationMemoryService;

    /** 混合检索——D47 前置调用 */
    private final HybridSearchService hybridSearchService;

    /** 澄清判定——D47 */
    private final ClarificationService clarificationService;

    /** D51 个性化检索 */
    private final RetrievalProfileService retrievalProfileService;

    /** D52 主动推荐配置 */
    private final RecommendationProperties recommendationProperties;

    /** D52 主动推荐服务 */
    private final RecommendationService recommendationService;

    /** 工具安全包装工厂 */
    private final SafeToolCallbackFactory safeToolCallbackFactory;

    /** 会话记忆 */
    private final ChatMemory chatMemory;

    /** D67 工具注册中心 */
    private final ToolRegistry toolRegistry;

    /** D69 前置：工具画像解析器 */
    private final ToolProfileResolver toolProfileResolver;

    /** ★ D69 新增：工具授权器 */
    private final ToolAuthorizer toolAuthorizer;

    // ==================== 运行时工具回调 ====================

    /**
     * 包装后的工具回调
     * <p>每个工具已经过 SafeToolCallback + AuthzToolCallback 双层包装。
     */
    private ToolCallback[] wrappedCallbacks;

    // ==================== 构造函数 ====================

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
            SafeToolCallbackFactory safeToolCallbackFactory,
            @Qualifier("redisChatMemory") ChatMemory chatMemory,
            ToolRegistry toolRegistry,
            ToolProfileResolver toolProfileResolver,
            ToolAuthorizer toolAuthorizer) {              // ★ D69 新增

        this.chatClientWithMemory = chatClientWithMemory;
        this.chatClientWithoutMemory = chatClientWithoutMemory;
        this.semanticCacheService = semanticCacheService;
        this.conversationMemoryService = conversationMemoryService;
        this.hybridSearchService = hybridSearchService;
        this.clarificationService = clarificationService;
        this.retrievalProfileService = retrievalProfileService;
        this.recommendationProperties = recommendationProperties;
        this.recommendationService = recommendationService;
        this.safeToolCallbackFactory = safeToolCallbackFactory;
        this.chatMemory = chatMemory;
        this.toolRegistry = toolRegistry;
        this.toolProfileResolver = toolProfileResolver;
        this.toolAuthorizer = toolAuthorizer;
    }

    // ==================== 工具回调生命周期（D68 三入口）====================

    /**
     * 保底初始化——Bean 创建后立即调用一次。
     * <p>此时 ToolRegistry 通常为空——建出的 wrappedCallbacks 是空数组。
     */
    @PostConstruct
    public void initToolCallbacks() {
        log.debug("[D68] ChatService @PostConstruct 保底初始化");
        rebuildWrappedCallbacks();
    }

    /**
     * 启动后重建——在 Bootstrap 注册工具完成之后执行。
     * <p>{@code @Order(100)} 晚于 ToolRegistrationBootstrap.bootstrap()（{@code @Order(50)}）。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void onApplicationReady() {
        log.info("[D68] ChatService 启动后重建 wrappedCallbacks");
        rebuildWrappedCallbacks();
    }

    /**
     * 运行时刷新回调——由 ToolRefreshService 在 refresh 后通知。
     */
    @Override
    public void onToolsRefreshed() {
        log.info("[D68] ChatService 收到工具刷新通知，重建 wrappedCallbacks");
        rebuildWrappedCallbacks();
    }

    /**
     * 从 ToolProfile 取工具 → Safe 包装 → Authz 包装。
     *
     * <h3>D69 完整包装链</h3>
     * <pre>
     *   ToolProfileResolver.resolveForConsumer("chat-service")
     *       ↓ ToolCallback[]
     *   SafeToolCallbackFactory.wrap(...)                    ← 重试/超时/日志
     *       ↓ ToolCallback[]
     *   AuthzToolCallback（本方法包装）                       ← 鉴权
     *       ↓ ToolCallback[]
     *   wrappedCallbacks
     * </pre>
     *
     * <h3>为什么鉴权在最外层</h3>
     * <ul>
     *   <li>鉴权失败不该触发重试——权限是确定性的</li>
     *   <li>鉴权失败不该消耗超时预算——快速返回</li>
     * </ul>
     */
    private synchronized void rebuildWrappedCallbacks() {
        // ① 从 profile 取工具
        ToolCallback[] profileCallbacks = toolProfileResolver.resolveForConsumer("chat-service");

        if (profileCallbacks.length == 0) {
            log.warn("[D68] chat-service profile 解析为空——Agent 当前无工具可用");
        }

        // ② 包 SafeToolCallback（重试 + 超时 + 日志 + 异常兜底）
        ToolCallback[] safeWrapped = safeToolCallbackFactory.wrap(profileCallbacks);

        // ③ ★ D69：再包 AuthzToolCallback（鉴权）—— 在 Safe 外层
        ToolCallback[] fullyWrapped = Arrays.stream(safeWrapped)
                .map(cb -> (ToolCallback) new AuthzToolCallback(cb, toolAuthorizer))
                .toArray(ToolCallback[]::new);

        this.wrappedCallbacks = fullyWrapped;

        log.info("[D69] 工具回调重建完成，共 {} 个（profile: chat-service, RBAC 已包装）",
                fullyWrapped.length);
    }

    // ==================== 简单调用（无记忆、无工具） ====================

    public String syncChat(String userInput) {
        return chatClientWithoutMemory.prompt()
                .user(userInput)
                .call()
                .content();
    }

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
     *
     * <h3>D69 改动</h3>
     * <p>
     * {@code toolContext(...)} 里新增 {@code securityLevel}——
     * 供 {@link AuthzToolCallback} 做工具级鉴权。
     */
    public Flux<String> streamChatWithMemory(String userInput,
                                             String conversationId,
                                             RagFilter ragFilter) {
        long startTime = System.currentTimeMillis();

        // ★ 归一化 filter
        RagFilter effectiveFilter = (ragFilter == null) ? RagFilter.empty() : ragFilter;

        // ★ 从 conversationId 提取纯 userId
        String fullUserId = ConversationIdUtils.extractFullUserId(conversationId);

        // ★ D53：构造过滤维度 Map
        Map<String, Object> filterDims = buildFilterDims(effectiveFilter);

        // ① 语义缓存查询（★ 含 ID 类 token 的 query 跳过——避免"订单 1001"命中"订单 2001"）
        String cachedAnswer = null;
        if (!shouldBypassSemanticCache(userInput)) {
            cachedAnswer = semanticCacheService.lookup(userInput, fullUserId, filterDims);
        } else {
            log.debug("[缓存] query 含 ID 类 token，跳过语义缓存: [{}]",TextUtils.truncate(userInput, 30));
        }
        if (cachedAnswer != null) {
            log.info("✅ [缓存命中] query=[{}]", TextUtils.truncate(userInput, 30));
            return Flux.just(cachedAnswer);
        }

        // ★ D51：读取检索画像
        RetrievalProfile profile = retrievalProfileService.get(fullUserId);

        // ==================== ② D47 前置 RAG 检索 ====================
        final List<Document> prefetchedDocs = fetchPrefetchedDocs(userInput, effectiveFilter, profile);

        // ==================== ③ D47 澄清判定 ====================
        if (clarificationService.isAmbiguous(userInput, prefetchedDocs)) {
            String clarifyMsg = clarificationService.buildClarification(userInput, prefetchedDocs);
            log.info("🤔 [反问] query=[{}] 耗时={}ms（跳过 LLM）",
                    TextUtils.truncate(userInput, 30),
                    System.currentTimeMillis() - startTime);
            return Flux.just(clarifyMsg);
        }

        // ==================== ④ 正常调 LLM ====================
        StringBuilder fullAnswer = new StringBuilder();
        long[] firstTokenTime = {0};

        // ★ D69：提取用户密级——传给 AuthzToolCallback
        int userSecurityLevel = extractUserSecurityLevel(effectiveFilter);

        Flux<String> mainStream = chatClientWithMemory.prompt()
                .user(userInput)
                .advisors(a -> {
                    a.param(ChatMemory.CONVERSATION_ID, conversationId);
                    if (!effectiveFilter.isEmpty()) {
                        a.param("rag_filter", effectiveFilter);
                    }
                    a.param("prefetched_docs", prefetchedDocs);
                })
                // ★ D69：toolContext 新增 securityLevel
                .toolContext(Map.of(
                        "userId", fullUserId,
                        "securityLevel", userSecurityLevel
                ))
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

                    String answer = fullAnswer.toString();
                    // ★ 同样跳过 store——不要往缓存里塞含 ID 的 query
                    if (!answer.isBlank() && !shouldBypassSemanticCache(userInput)) {
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
        return mainStream.concatWith(
                Flux.defer(() -> buildRecommendationFlux(
                        userInput, conversationId, prefetchedDocs, effectiveFilter))
        );
    }

    /**
     * 从 RagFilter 提取用户密级（D69）
     * <p>
     * ChatController 已经把 {@code SessionUtils.getSecurityLevel(request)}
     * 塞进了 {@code RagFilter.securityLevelMax}——这里直接读。
     * <p>
     * 兜底 1——未登录 / filter 为空时只能调最低密级工具。
     */
    private int extractUserSecurityLevel(RagFilter filter) {
        if (filter == null || filter.securityLevelMax() == null) {
            return 1;
        }
        return filter.securityLevelMax();
    }

    /**
     * 判断是否应跳过语义缓存（D69 补丁）
     *
     * <h3>为什么需要这个方法</h3>
     * <p>
     * 语义缓存基于 EmbeddingModel 的余弦相似度——
     * "查一下订单 1001 的状态" 和 "查一下订单 2001 的状态" 相似度 &gt; 0.95，
     * 会被误判为"同一个问题"。但它们的答案完全不同。
     *
     * <p>这类 query 的特征：<b>句式相同，只有 ID/数字不同</b>。
     * 语义相似度对它们失效——必须跳过缓存，走真实检索。
     *
     * <h3>识别规则</h3>
     * <ul>
     *   <li>含 4 位以上连续数字 → 疑似 ID（订单号、合同号、手机号）</li>
     *   <li>含 UUID 格式（8-4-4-4-12） → 疑似文档 ID</li>
     * </ul>
     *
     * <h3>误判的影响</h3>
     * <p>
     * 即使误判（本来是普通问题却被识别成含 ID），
     * 也只是"这条 query 不走缓存"——不会有正确性问题，只是性能略差。
     * 所以规则可以宽松些——<b>宁可多跳一次缓存，不要错误命中</b>。
     *
     * <h3>更彻底的方案</h3>
     * <p>
     * 生产环境建议改为"参数化模板缓存"——
     * 把 query 里的 ID 剥离后作为模板 key，ID 值作为参数存储。
     * 例：{@code "查订单{id}状态"} → {@code {1001: "已发货", 2001: "待收货"}}。
     * 那样句式相同的查询能命中模板，ID 不同也不冲突。
     * 这是后续演进方向，当前先用"跳过"解决正确性问题。
     */
    private boolean shouldBypassSemanticCache(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        // ① 含 4 位以上连续数字 → 疑似 ID
        if (query.matches(".*\\d{4,}.*")) {
            return true;
        }
        // ② 含 UUID 格式
        if (query.matches(".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}.*")) {
            return true;
        }
        return false;
    }

    // ==================== 清空记忆 ====================

    /**
     * 清空某个会话的全部记忆
     * <p>
     * <b>"彻底遗忘"语义</b>：清空 = 用户以为删掉的东西真的消失了。两处一起清：
     * <ol>
     *   <li><b>CHAT:*</b>——当前会话的滚动窗口（短期记忆）</li>
     *   <li><b>conv-mem:*</b>——该会话的所有轮次向量（D50 长期历史）</li>
     * </ol>
     * <p>
     * <b>不动</b>：LTM / USER_PREF / USER_INTEREST / semantic-cache。
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
        boolean vectorDeleted = conversationMemoryService.deleteByConversationId(conversationId);
        if (vectorDeleted) {
            log.info("✅ 已清空 conv-mem 长期历史: conversationId={}", conversationId);
        } else {
            log.warn("⚠️ conv-mem 未清干净（可能本来就没数据）: conversationId={}",
                    conversationId);
        }
    }

    // ==================== 异常友好化 ====================

    private String toFriendlyMessage(Throwable e) {
        if (e instanceof WebClientResponseException w) {
            int s = w.getStatusCode().value();
            if (s == 401) return "AI 服务认证失败，请联系管理员";
            if (s == 429) return "请求太频繁，请稍后重试";
            return "AI 服务暂时不可用，请稍后重试";
        }
        if (e instanceof RedisConnectionFailureException) {
            return "记忆服务暂时不可用，请稍后重试";
        }
        if (e instanceof IllegalStateException) {
            return e.getMessage() == null ? "请先登录" : e.getMessage();
        }
        return "服务出了点问题，请稍后重试";
    }

    // ==================== 辅助方法 ====================

    private List<Document> fetchPrefetchedDocs(String query, RagFilter filter, RetrievalProfile profile) {
        try {
            List<Document> docs = hybridSearchService.search(query, filter, profile);
            log.info("🔍 [前置检索] query=[{}] 返回 {} 条",
                    TextUtils.truncate(query, 30), docs.size());
            return docs;
        } catch (Exception e) {
            log.warn("🔍 [前置检索] 失败，降级为空结果", e);
            return List.of();
        }
    }

    private Flux<String> buildRecommendationFlux(String userInput,
                                                 String conversationId,
                                                 List<Document> alreadyShown,
                                                 RagFilter filter) {
        try {
            if (!recommendationProperties.isEnabled()) {
                return Flux.empty();
            }

            Set<String> excludeIds = alreadyShown == null
                    ? Set.of()
                    : alreadyShown.stream()
                    .map(d -> (String) d.getMetadata().get("doc_id"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            List<String> historyQueries = extractRecentQueries(
                    conversationId, recommendationProperties.getRecentQueryCount());

            List<RecommendedDoc> recs = recommendationService.recommend(
                    userInput, historyQueries, excludeIds, filter);

            if (recs.isEmpty()) {
                log.info("[D52] 无推荐结果，跳过");
                return Flux.empty();
            }

            String block = recommendationService.render(recs);
            log.info("[D52] 追加 {} 条推荐: userId={}, query=[{}]",
                    recs.size(),
                    ConversationIdUtils.extractFullUserId(conversationId),
                    TextUtils.truncate(userInput, 30));
            return Flux.just(block);

        } catch (Exception e) {
            log.warn("[D52] 推荐失败，降级为空", e);
            return Flux.empty();
        }
    }

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