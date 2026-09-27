package org.example.rag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.rag.entity.RagChunk;

import java.util.List;

@Mapper
public interface RagChunkMapper {

    /**
     * 批量插入 chunk（入库时用）
     */
    int batchInsert(@Param("list") List<RagChunk> chunks);

    /**
     * MySQL 全文检索（关键词检索）
     *
     * @param query 用户问题
     * @param topK  返回条数
     */
    List<RagChunk> fulltextSearch(@Param("query") String query,
                                  @Param("topK") int topK);

    /**
     * 按 docId 删除（重新入库时清理旧数据）
     */
    int deleteByDocId(@Param("docId") String docId);

    /**
     * 统计总数
     */
    long count();
}