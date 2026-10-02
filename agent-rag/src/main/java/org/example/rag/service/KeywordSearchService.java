package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.entity.RagChunk;
import org.example.rag.mapper.RagChunkMapper;
import org.example.rag.model.RagFilter;
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
     * 构造 MySQL BOOLEAN MODE 查询串
     * <p>
     * <b>设计目标</b>：弥补 MySQL ngram 分词粒度太粗（2-gram）导致的误召回。
     * <p>
     * <b>切分策略</b>：
     * - 英文/数字：按非字母数字切分
     * - 中文：滑动窗口取相邻 2 字（与 ngram_token_size=2 对齐）
     * <p>
     * <b>为什么去掉原来的 + 前缀</b>：
     * 1. 中文没有空格，"签约家庭医生后能享受哪些服务" 用原逻辑会被当成 1 个词，
     *    加 + 后要求这个 14 字串整体出现——几乎不可能命中
     * 2. MATCH AGAINST 在 OR 语义下，score 已按"匹配 token 数"打分——
     *    天然形成"越相关排越前"，本身就在做"精确度排序"
     * 3. 真正的精确度兜底在 TEI 重排——关键词路只负责"召回候选"
     * <p>
     * <b>如果仍想保留部分强约束</b>：对 query 的前 2 个 2-gram 加 + ——
     * 中文句子的前 2 字通常是主题词（"签约"、"家庭"），用 + 强制出现有意义；
     * 后面的 token 用 OR 语义放宽召回。见下方注释。
     */
    private String buildBooleanQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        List<String> tokens = new ArrayList<>();

        // ① 英文/数字：按非字母数字切分
        for (String token : query.split("[^a-zA-Z0-9]+")) {
            if (token.length() >= 2) {
                tokens.add(token);
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

        // ③ 组装查询串
        //    ★ 方案 A（推荐，召回优先）：
        //       全部用 OR 语义，靠 score 排序 + TEI 重排兜精确度
        tokens.addAll(zhTokens);
        return tokens.stream().distinct().collect(Collectors.joining(" "));

        //    ★ 方案 B（如果实测"误召回太多"，用这个）：
        //       前 2 个 2-gram 加 + 强制出现（锁定主题词），其余用 OR
        //       取消上面的 return，改用：
        //
        //  StringBuilder sb = new StringBuilder();
        //  // 英文/数字 token 用 OR
        //  for (String t : tokens) sb.append(t).append(" ");
        //  // 前 2 个中文 2-gram 用 + 强制
        //  for (int i = 0; i < Math.min(2, zhTokens.size()); i++) {
        //      sb.append("+").append(zhTokens.get(i)).append(" ");
        //  }
        //  // 剩余中文 2-gram 用 OR
        //  for (int i = 2; i < zhTokens.size(); i++) {
        //      sb.append(zhTokens.get(i)).append(" ");
        //  }
        //  return sb.toString().trim();
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