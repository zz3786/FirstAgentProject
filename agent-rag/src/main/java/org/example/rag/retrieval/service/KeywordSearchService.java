package org.example.rag.retrieval.service;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.shared.entity.RagChunk;
import org.example.rag.shared.mapper.RagChunkMapper;
import org.example.rag.shared.model.RagFilter;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KeywordSearchService {

    private final RagChunkMapper ragChunkMapper;

    public KeywordSearchService(RagChunkMapper ragChunkMapper) {
        this.ragChunkMapper = ragChunkMapper;
    }

    /**
     * 关键词检索（带过滤）
     * <p>
     * ★ D46：过滤条件通过 MyBatis 动态 SQL 实现
     */
    public List<Document> search(String query, int topK, RagFilter filter) {
        try {
            String booleanQuery = buildBooleanQuery(query);
            List<RagChunk> chunks = ragChunkMapper.fulltextSearchWithFilter(
                    booleanQuery, topK,
                    filter == null ? null : filter.tenantId(),
                    filter == null ? null : filter.departments(),
                    filter == null ? null : filter.yearFrom(),
                    filter == null ? null : filter.yearTo(),
                    filter == null ? null : filter.docTypes(),
                    // ★ D46 新增
                    filter == null ? null : filter.securityLevelMax(),
                    filter == null ? null : filter.statuses()
            );
            return chunks.stream().map(this::toDocument).toList();
        } catch (Exception e) {
            log.error("关键词检索失败, query={}", query, e);
            return List.of();
        }
    }

    public List<Document> search(String query, int topK) {
        return search(query, topK, null);
    }

    /**
     * 构造 MySQL BOOLEAN MODE 查询串（方案 B：前 2 个主题词强制匹配）
     *
     * <h3>为什么用方案 B 而不是方案 A</h3>
     * <p>
     * 方案 A（全 OR 匹配）在真实环境暴露了问题：
     * <ul>
     *   <li>10 条召回里有大量"擦边球"数据（只匹配到 1 个无关 ngram）</li>
     *   <li>关键词路把候选集塞满 10 条，把向量路的有效结果挤掉</li>
     *   <li>TEI 重排要做"擦屁股"工作，输入噪声太多</li>
     * </ul>
     *
     * <h3>方案 B 的核心策略</h3>
     * <ul>
     *   <li>中文 query 的<b>前 2 个 2-gram</b>加 {@code +}——强制出现</li>
     *   <li>剩余 2-gram 不加 {@code +}——放宽召回</li>
     *   <li>英文/数字不加 {@code +}——避免误杀</li>
     * </ul>
     *
     * <h3>为什么前 2 个是"主题词"</h3>
     * <p>
     * 中文的自然语序——主题词通常在前：
     * <ul>
     *   <li>"家庭医生签约注意事项"——前 4 字是核心主体</li>
     *   <li>"怎么找社区医生签协议"——前 4 字是动作方向</li>
     * </ul>
     * 后 2 个 ngram 常是"注意事项""怎么办"这种修饰语——加 {@code +} 会误杀。
     *
     * <h3>举例</h3>
     * <pre>
     *   query = "家庭医生签约的注意事项"
     *   切分 → [家庭, 庭医, 医生, 生签, 签约, 约的, 的注, 注意, 意事, 事项]
     *   组装 → "+家庭 +庭医 医生 生签 签约 约的 的注 注意 意事 事项"
     *
     *   效果：文档必须同时包含"家庭"和"庭医"（覆盖了"家庭医"的语义），
     *        剩余的 ngram 只要出现一个就能被召回。
     * </pre>
     */
    private String buildBooleanQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        // ① 英文/数字：按非字母数字切分
        List<String> enTokens = new ArrayList<>();
        for (String token : query.split("[^a-zA-Z0-9]+")) {
            if (token.length() >= 2) {
                enTokens.add(token);
            }
        }

        // ② 中文：滑动窗口取相邻 2 字
        List<String> zhTokens = new ArrayList<>();
        for (int i = 0; i < query.length() - 1; i++) {
            char c1 = query.charAt(i);
            char c2 = query.charAt(i + 1);
            if (isChinese(c1) && isChinese(c2)) {
                zhTokens.add("" + c1 + c2);
            }
        }

        // ③ 组装查询串（方案 B）
        StringBuilder sb = new StringBuilder();

        // 3a. 英文/数字 token 用 OR 语义（不加 +）
        for (String t : enTokens) {
            sb.append(t).append(" ");
        }

        // 3b. 前 2 个中文 2-gram 加 + 强制匹配
        int forceCount = Math.min(2, zhTokens.size());
        for (int i = 0; i < forceCount; i++) {
            sb.append("+").append(zhTokens.get(i)).append(" ");
        }

        // 3c. 剩余中文 2-gram 用 OR 语义放宽召回
        for (int i = forceCount; i < zhTokens.size(); i++) {
            sb.append(zhTokens.get(i)).append(" ");
        }

        // ④ 去重——注意保留 + 前缀
        //   场景：query = "家庭家庭" → 切分得 [家庭, 庭家, 家庭]
        //   如果简单 distinct 会保留 +家庭 和 家庭 两个，语义重复
        //   我们用 LinkedHashSet 保留"带 + 优先"的版本
        String raw = sb.toString().trim();
        if (raw.isEmpty()) {
            return "";
        }

        // 用 Map 去重——key = 去掉 + 的词，value = 完整 token（含 + 或不含）
        // 如果同一个词既出现 "+xxx" 又出现 "xxx"，保留 "+xxx"（强约束优先）
        java.util.Map<String, String> deduped = new java.util.LinkedHashMap<>();
        for (String part : raw.split("\\s+")) {
            if (part.isEmpty()) {
                continue;
            }
            boolean hasPlus = part.startsWith("+");
            String word = hasPlus ? part.substring(1) : part;
            String existing = deduped.get(word);

            // 保留带 + 的版本（强约束优先）
            if (existing == null || (hasPlus && !existing.startsWith("+"))) {
                deduped.put(word, part);
            }
        }

        return String.join(" ", deduped.values());
    }

    private boolean isChinese(char c) {
        return c >= '\u4e00' && c <= '\u9fa5';
    }

    private Document toDocument(RagChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("doc_id", chunk.getDocId());
        metadata.put("chunk_index", chunk.getChunkIndex());
        metadata.put("source", chunk.getSource());
        metadata.put("file_path", chunk.getFilePath());
        metadata.put("score", chunk.getScore());
        metadata.put("retrieval_type", "keyword");

        if (chunk.getTenantId() != null) {
            metadata.put("tenant_id", chunk.getTenantId());
        }

        if (chunk.getDepartment() != null) {
            metadata.put("department", chunk.getDepartment());
        }
        if (chunk.getYear() != null) {
            metadata.put("year", chunk.getYear());
        }
        if (chunk.getContentType() != null) {
            metadata.put("content_type", chunk.getContentType());
        }
        // ★ D46 新增
        if (chunk.getSecurityLevel() != null) {
            metadata.put("security_level", chunk.getSecurityLevel());
        }
        if (chunk.getStatus() != null) {
            metadata.put("status", chunk.getStatus());
        }

        // ★ 新增：页码 + 总切片数
        if (chunk.getPageNumber() != null) {
            metadata.put("page_number", chunk.getPageNumber());
        }
        if (chunk.getTotalChunks() != null) {
            metadata.put("total_chunks", chunk.getTotalChunks());
        }

        return new Document(chunk.getContent(), metadata);
    }
}