/**
 * 检索模块
 * <p>
 * <b>职责</b>：query → 混合检索 → 精排 → 注入 Prompt
 * <p>
 * <b>核心类</b>：
 * <ul>
 *   <li>{@link org.example.rag.retrieval.service.HybridSearchService}——混合检索</li>
 *   <li>{@link org.example.rag.retrieval.advisor.RagAdvisor}——Prompt 注入</li>
 * </ul>
 */
package org.example.rag.retrieval;