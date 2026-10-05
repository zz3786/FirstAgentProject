package org.example.workflow.core.dsl.expression;

import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 表达式求值引擎
 * <p>
 * <b>为什么用 SpEL</b>：
 * <ul>
 *   <li>Spring 自带——不引新依赖</li>
 *   <li>语法强大——支持比较、逻辑、集合、方法调用</li>
 *   <li>性能好——编译后的表达式缓存复用</li>
 * </ul>
 *
 * <h3>语法示例</h3>
 * <pre>
 *   ${branchTaken == 'PaymentReminderNode'}
 *   ${amount > 1000}
 *   ${status in ['SHIPPED', 'SIGNED']}
 *   ${userLevel >= 3 and amount > 500}
 * </pre>
 */
@Slf4j
@Component
public class ExpressionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();

    /** 表达式编译缓存——避免每次重新解析 */
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    /**
     * 求值为 boolean
     */
    public boolean evaluateBoolean(String expression, Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) {
            return true;   // 空表达式视为 true
        }
        try {
            String stripped = stripPlaceholder(expression);
            Expression expr = cache.computeIfAbsent(stripped, parser::parseExpression);

            EvaluationContext ctx = buildContext(variables);
            Boolean result = expr.getValue(ctx, Boolean.class);
            return result != null && result;

        } catch (Exception e) {
            log.warn("表达式求值失败: expr=[{}], 默认返回 false. err={}",
                    expression, e.getMessage());
            return false;
        }
    }

    /**
     * 求值为字符串
     */
    public String evaluateString(String expression, Map<String, Object> variables) {
        if (expression == null) return null;

        // 支持 ${...} 混在文本里——如 "订单 ${orderId} 处理完成"
        if (!expression.contains("${")) {
            return expression;
        }

        try {
            String resolved = expression;
            // 提取所有 ${...} 并求值
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\$\\{([^}]+)\\}").matcher(expression);

            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String innerExpr = m.group(1);
                Object value = evaluateObject(innerExpr, variables);
                m.appendReplacement(sb, value == null ? "" :
                        java.util.regex.Matcher.quoteReplacement(value.toString()));
            }
            m.appendTail(sb);
            return sb.toString();

        } catch (Exception e) {
            log.warn("字符串求值失败: expr=[{}], 返回原文. err={}",
                    expression, e.getMessage());
            return expression;
        }
    }

    /**
     * 求值为任意对象
     */
    public Object evaluateObject(String expression, Map<String, Object> variables) {
        try {
            String stripped = stripPlaceholder(expression);
            Expression expr = cache.computeIfAbsent(stripped, parser::parseExpression);
            return expr.getValue(buildContext(variables));
        } catch (Exception e) {
            log.warn("求值失败: expr=[{}], err={}", expression, e.getMessage());
            return null;
        }
    }

    /** 去掉 ${} 包裹 */
    private String stripPlaceholder(String expr) {
        String s = expr.trim();
        if (s.startsWith("${") && s.endsWith("}")) {
            return s.substring(2, s.length() - 1);
        }
        return s;
    }

    /** 构造 SpEL 上下文 */
    private EvaluationContext buildContext(Map<String, Object> variables) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        if (variables != null) {
            ctx.setVariables(variables);
            // 同时把变量作为 root object 的属性——支持直接引用 ${branchTaken} 而不是 ${#branchTaken}
            Map<String, Object> root = new HashMap<>(variables);
            ctx.setRootObject(new MapWrapper(root));
        }
        return ctx;
    }

    /**
     * SpEL 的 root object 包装器
     * <p>
     * 让 SpEL 支持直接写 {@code ${branchTaken}} 而不是 {@code ${#branchTaken}}。
     */
    public static class MapWrapper {
        private final Map<String, Object> map;

        public MapWrapper(Map<String, Object> map) {
            this.map = map;
        }

        public Object get(String key) {
            return map.get(key);
        }
    }
}