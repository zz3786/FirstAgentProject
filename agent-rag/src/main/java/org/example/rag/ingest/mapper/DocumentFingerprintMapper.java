package org.example.rag.ingest.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.rag.ingest.entity.DocumentFingerprint;

import java.util.List;

@Mapper
public interface DocumentFingerprintMapper {

    /**
     * 按文件路径查指纹
     */
    DocumentFingerprint findByFilePath(@Param("filePath") String filePath);

    /**
     * 查所有指纹记录
     */
    List<DocumentFingerprint> findAll();

    /**
     * 插入
     */
    int insert(DocumentFingerprint fp);

    /**
     * 更新
     */
    int update(DocumentFingerprint fp);

    /**
     * 按 docId 删除
     */
    int deleteByDocId(@Param("docId") String docId);

    /**
     * 按文件路径删除
     */
    int deleteByFilePath(@Param("filePath") String filePath);

    /**
     * 统计总数
     */
    long count();
}