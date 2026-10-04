/**
 * 文件摄取模块
 * <p>
 * <b>职责</b>：文档扫描 → 解析 → 切片 → 入库 → 向量化
 * <p>
 * <b>核心类</b>：
 * <ul>
 *   <li>{@link org.example.rag.ingest.service.DocumentIngestService}——主流程</li>
 *   <li>{@link org.example.rag.ingest.service.IncrementalUpdateService}——增量扫描</li>
 *   <li>{@link org.example.rag.ingest.service.AsyncIndexService}——异步批量</li>
 * </ul>
 */
package org.example.rag.ingest;