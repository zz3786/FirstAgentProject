package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.entity.RagChunk;
import org.example.rag.mapper.RagChunkMapper;
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
     * 关键词检索 —— 返回 Spring AI Document
     */
    public List<Document> search(String query, int topK) {
        try {
            List<RagChunk> chunks = ragChunkMapper.fulltextSearch(buildBooleanQuery(query), topK);
            return chunks.stream().map(this::toDocument).toList();
        } catch (Exception e) {
            log.error("关键词检索失败, query={}", query, e);
            return List.of();
        }
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
        return new Document(chunk.getContent(), metadata);
    }
}