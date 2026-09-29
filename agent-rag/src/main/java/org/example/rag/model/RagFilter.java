package org.example.rag.model;

import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

/**
 * RAG 检索过滤条件（与业务解耦的中立模型）
 * <p>
 * <b>为什么不用 Map&lt;String, Object&gt;</b>：
 * Map 丢失类型信息——调用方不知道 "year" 该传 String 还是 Integer，也不知道哪些 key 是允许的。
 * 用 record 把"合法过滤维度"显式声明出来，调用方一看就懂，IDE 也能自动补全。
 * <p>
 * <b>为什么字段可空</b>：
 * 每个过滤维度都是可选的——调用方可能只按部门过滤，不按时间。
 * 为 null 表示"该维度不参与过滤"。
 */
public record RagFilter(
        /** 部门过滤（支持多值 IN） */
        List<String> departments,
        /** 起始年份（>=），null 表示不限 */
        Integer yearFrom,
        /** 截止年份（<=），null 表示不限 */
        Integer yearTo,
        /** 文档类型过滤（如 "制度"、"合同"、"报告"） */
        List<String> docTypes,
        /** 来源文件过滤（按原始文件名精确匹配） */
        List<String> sources
) {

    /** 空过滤——不施加任何条件 */
    public static RagFilter empty() {
        return new RagFilter(null, null, null, null, null);
    }

    /** 是否为空过滤（全部维度为 null） */
    public boolean isEmpty() {
        return (departments == null || departments.isEmpty())
                && yearFrom == null
                && yearTo == null
                && (docTypes == null || docTypes.isEmpty())
                && (sources == null || sources.isEmpty());
    }

    /**
     * 转换为 Spring AI 的可移植 Filter.Expression
     * <p>
     * <b>为什么用 and 链式拼接而非 or</b>：
     * 过滤维度之间是"交集"语义——"研发部 + 2024年之后"意味着两个条件都要满足。
     * 每个维度内部用 IN（同一维度的多个值取并集）。
     * <p>
     * <b>null 的处理</b>：为 null 的维度直接跳过，不加入 and 链。
     * 这样调用方可以只传关心的维度，其余留空。
     */
    public Filter.Expression toExpression() {
        var b = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder();

        // 收集所有非空条件
        java.util.List<org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op> ops =
                new java.util.ArrayList<>();

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

        // 无任何条件 → 返回 null（表示不过滤）
        if (ops.isEmpty()) {
            return null;
        }

        // 链式 and：AND(AND(c1, c2), c3) ...
        var result = ops.get(0);
        for (int i = 1; i < ops.size(); i++) {
            result = b.and(result, ops.get(i));
        }
        return result.build();
    }
}