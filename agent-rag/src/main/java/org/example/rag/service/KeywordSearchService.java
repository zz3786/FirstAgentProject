package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.entity.RagChunk;
import org.example.rag.mapper.RagChunkMapper;
import org.example.rag.model.RagFilter;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            // 把过滤维度展开为 SQL 可用的参数
            List<RagChunk> chunks = ragChunkMapper.fulltextSearchWithFilter(
                    booleanQuery, topK,
                    filter == null ? null : filter.departments(),
                    filter == null ? null : filter.yearFrom(),
                    filter == null ? null : filter.yearTo(),
                    filter == null ? null : filter.docTypes()
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

    //优化关键词检索，以弥补MySQL ngram分词粒度太粗导致的误召回问题
    private String buildBooleanQuery(String query) {
        // 简单按非中英文切分，获取关键词
        String[] words = query.split("[^\\u4e00-\\u9fa5a-zA-Z0-9]+");
        return Arrays.stream(words)
                .filter(w -> w.length() >= 2) // 过滤掉单字
                .map(w -> "+" + w)             // 每个词都必须出现
                .collect(Collectors.joining(" "));
    }

    private Document toDocument(RagChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("doc_id", chunk.getDocId());
        metadata.put("chunk_index", chunk.getChunkIndex());
        metadata.put("source", chunk.getSource());
        metadata.put("file_path", chunk.getFilePath());
        metadata.put("score", chunk.getScore());
        metadata.put("retrieval_type", "keyword");

        // ★ D46 新增：过滤维度也要带上
        //   融合阶段 buildKey 用 doc_id + chunk_index
        //   但展示 / 调试时能看出这是哪个部门的
        if (chunk.getDepartment() != null) {
            metadata.put("department", chunk.getDepartment());
        }
        if (chunk.getYear() != null) {
            metadata.put("year", chunk.getYear());
        }
        if (chunk.getContentType() != null) {
            metadata.put("content_type", chunk.getContentType());
        }

        return new Document(chunk.getContent(), metadata);
    }
}