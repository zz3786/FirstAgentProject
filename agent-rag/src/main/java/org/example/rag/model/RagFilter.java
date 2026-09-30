package org.example.rag.model;

import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 检索过滤条件
 * <p>
 * <b>D46 扩展</b>：新增 securityLevelMax 和 statuses 两个维度。
 * <p>
 * <b>为什么用 record + 保留旧构造</b>：
 * 现有测试和代码用的是 5 参构造（departments/yearFrom/yearTo/docTypes/sources）。
 * 加 7 参构造会破坏所有调用点——保留一个 5 参重载，旧代码零改动。
 */
public record RagFilter(
        List<String> departments,
        Integer yearFrom,
        Integer yearTo,
        List<String> docTypes,
        List<String> sources,
        // ★ D46 新增
        Integer securityLevelMax,      // 用户最大密级（过滤 security_level <= 该值）
        List<String> statuses          // 允许的文档状态
) {

    /**
     * 向后兼容的 5 参构造
     * <p>
     * 旧代码 new RagFilter(depts, yf, yt, types, srcs) 依然可用——
     * securityLevelMax = null（不过滤密级），statuses = null（不过滤状态）。
     */
    public RagFilter(List<String> departments, Integer yearFrom, Integer yearTo,
                     List<String> docTypes, List<String> sources) {
        this(departments, yearFrom, yearTo, docTypes, sources, null, null);
    }

    /**
     * 空过滤——不施加任何条件
     */
    public static RagFilter empty() {
        return new RagFilter(null, null, null, null, null, null, null);
    }

    /**
     * 只按密级过滤——生产最常用的入口
     * <p>
     * 场景：用户只能看 <= 自己密级的文档。
     * 其他维度留空，默认状态过滤为 active。
     */
    public static RagFilter forUser(int userSecurityLevel) {
        return new RagFilter(
                null, null, null, null, null,
                userSecurityLevel,
                List.of("active")        // ★ 默认只查有效文档
        );
    }

    public boolean isEmpty() {
        return (departments == null || departments.isEmpty())
                && yearFrom == null
                && yearTo == null
                && (docTypes == null || docTypes.isEmpty())
                && (sources == null || sources.isEmpty())
                && securityLevelMax == null
                && (statuses == null || statuses.isEmpty());
    }

    /**
     * 转换为 Spring AI 的可移植 Filter.Expression
     */
    public Filter.Expression toExpression() {
        var b = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder();
        var ops = new ArrayList<org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op>();

        if (departments != null && !departments.isEmpty()) {
            ops.add(b.in("department", departments.toArray()));
        }
        if (yearFrom != null) {
            ops.add(b.gte("year", yearFrom));
        }
        if (yearTo != null) {
            ops.add(b.lte("year", yearTo));
        }
        if (docTypes != null && !docTypes.isEmpty()) {
            ops.add(b.in("content_type", docTypes.toArray()));
        }
        if (sources != null && !sources.isEmpty()) {
            ops.add(b.in("source", sources.toArray()));
        }
        // ★ D46 新增
        if (securityLevelMax != null) {
            ops.add(b.lte("security_level", securityLevelMax));
        }
        if (statuses != null && !statuses.isEmpty()) {
            ops.add(b.in("status", statuses.toArray()));
        }

        if (ops.isEmpty()) {
            return null;
        }

        var result = ops.get(0);
        for (int i = 1; i < ops.size(); i++) {
            result = b.and(result, ops.get(i));
        }
        return result.build();
    }


    /**
     * 生成用于缓存 key 的后缀字符串
     * <p>
     * <b>为什么放在 RagFilter 而不是 SemanticCacheService</b>：
     * SemanticCacheService 在 agent-cache 模块，不能依赖 agent-rag 的 RagFilter。
     * 由 RagFilter 自己"自报家门"——调用方只需 filter.cacheKeySuffix()。
     * <p>
     * <b>格式</b>：
     * <pre>
     * {dept1,dept2}|{yearFrom}-{yearTo}|{type1,type2}|{src1,src2}|{secLevel}|{status1,status2}
     * </pre>
     * 集合维度排序后拼接——保证同集合不同顺序生成同一 key。
     * <p>
     * 无过滤时返回空字符串——调用方可直接拼到 tenantId 后面。
     */
    public String cacheKeySuffix() {
        if (isEmpty()) {
            return "";
        }
        return joinSorted(departments) + "|"
                + (yearFrom == null ? "" : yearFrom) + "-"
                + (yearTo == null ? "" : yearTo) + "|"
                + joinSorted(docTypes) + "|"
                + joinSorted(sources) + "|"
                + (securityLevelMax == null ? "" : securityLevelMax) + "|"
                + joinSorted(statuses);
    }

    private String joinSorted(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return list.stream().sorted().collect(java.util.stream.Collectors.joining(","));
    }
}