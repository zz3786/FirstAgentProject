package org.example.rag.shared.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.rag.shared.entity.RagChunk;

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
                                  @Param("topK") int topK,
                                  @Param("tenantId") String tenantId);

    /**
     * 按 docId 删除（重新入库时清理旧数据）
     */
    int deleteByDocId(@Param("docId") String docId);

    /**
     * 统计总数
     */
    long count();

    List<RagChunk> fulltextSearchWithFilter(
            @Param("query") String query,
            @Param("topK") int topK,
            @Param("tenantId") String tenantId,
            @Param("departments") List<String> departments,
            @Param("yearFrom") Integer yearFrom,
            @Param("yearTo") Integer yearTo,
            @Param("docTypes") List<String> docTypes,
            @Param("securityLevelMax") Integer securityLevelMax,   // ★ 新增
            @Param("statuses") List<String> statuses               // ★ 新增
    );
}