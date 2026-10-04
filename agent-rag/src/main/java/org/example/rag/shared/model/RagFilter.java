package org.example.rag.shared.model;

import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 检索过滤条件
 * <p>
 * <b>D54 扩展</b>：新增 tenantId——租户隔离。
 * <p>
 * <b>D46 扩展</b>：新增 securityLevelMax 和 statuses 两个维度。
 * <p>
 * <b>为什么用 record + 保留旧构造</b>：
 * 现有测试和代码用的是 5 参 / 7 参构造——
 * 加 tenantId 不能破坏它们——保留两个兼容构造。
 * <p>
 * <b>字段顺序</b>：tenantId 放最前——租户是"最外层硬过滤"，
 * 语义上比部门/密级更高优先级。
 */
public record RagFilter(
        // ★ D54 新增：租户 ID
        String tenantId,

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
     * 向后兼容的 5 参构造（D45 及更早）
     * <p>
     * 旧代码 new RagFilter(depts, yf, yt, types, srcs) 依然可用——
     * tenantId / securityLevelMax / statuses 全为 null。
     */
    public RagFilter(List<String> departments, Integer yearFrom, Integer yearTo,
                     List<String> docTypes, List<String> sources) {
        this(null, departments, yearFrom, yearTo, docTypes, sources, null, null);
    }

    /**
     * 向后兼容的 7 参构造（D46）
     * <p>
     * 旧代码 new RagFilter(depts, yf, yt, types, srcs, sec, status) 依然可用——
     * tenantId = null（不过滤租户）。
     */
    public RagFilter(List<String> departments, Integer yearFrom, Integer yearTo,
                     List<String> docTypes, List<String> sources,
                     Integer securityLevelMax, List<String> statuses) {
        this(null, departments, yearFrom, yearTo, docTypes, sources,
                securityLevelMax, statuses);
    }

    /**
     * 空过滤——不施加任何条件
     */
    public static RagFilter empty() {
        return new RagFilter(null, null, null, null, null, null, null, null);
    }

    /**
     * 只按密级过滤（兼容老调用）
     */
    public static RagFilter forUser(int userSecurityLevel) {
        return forUser(null, userSecurityLevel);
    }

    /**
     * ★ D54：只按租户 + 密级过滤——生产最常用
     * <p>
     * 场景：用户只能看自己租户下、且密级 <= 自己的文档。
     * 其他维度留空，默认状态过滤为 active。
     */
    public static RagFilter forUser(String tenantId, int userSecurityLevel) {
        return new RagFilter(
                tenantId,
                null, null, null, null, null,
                userSecurityLevel,
                List.of("active")
        );
    }

    public boolean isEmpty() {
        return (tenantId == null || tenantId.isBlank())
                && (departments == null || departments.isEmpty())
                && yearFrom == null
                && yearTo == null
                && (docTypes == null || docTypes.isEmpty())
                && (sources == null || sources.isEmpty())
                && securityLevelMax == null
                && (statuses == null || statuses.isEmpty());
    }

    /**
     * 转换为 Spring AI 的可移植 Filter.Expression
     * <p>
     * <b>D54 关键</b>：tenant_id 是"硬过滤"——必须放在最前，
     * 保证即使其他维度为空，租户过滤也生效。
     */
    public Filter.Expression toExpression() {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var ops = new ArrayList<FilterExpressionBuilder.Op>();

        // ★ D54：租户过滤——放最前，硬约束
        if (tenantId != null && !tenantId.isBlank()) {
            ops.add(b.eq("tenant_id", tenantId));
        }

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
     * <b>D54 更新</b>：tenantId 拼在最前——避免跨租户缓存命中。
     * <p>
     * <b>格式</b>：
     * <pre>
     * {tenantId}|{dept1,dept2}|{yearFrom}-{yearTo}|{types}|{sources}|{sec}|{statuses}
     * </pre>
     */
    public String cacheKeySuffix() {
        if (isEmpty()) {
            return "";
        }
        return (tenantId == null ? "" : tenantId) + "|"
                + joinSorted(departments) + "|"
                + (yearFrom == null ? "" : yearFrom) + "-"
                + (yearTo == null ? "" : yearTo) + "|"
                + joinSorted(docTypes) + "|"
                + joinSorted(sources) + "|"
                + (securityLevelMax == null ? "" : securityLevelMax) + "|"
                + joinSorted(statuses);
    }

    private String joinSorted(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return list.stream().sorted().collect(Collectors.joining(","));
    }
}