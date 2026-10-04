package org.example.common.utils;

/**
 * 文本处理工具
 * <p>
 * 集中放置"截断、清洗、规范化"这类纯字符串操作——
 * 之前各 Service 都自己写了一版 private truncate，重复度高且行为略有差异。
 */
public final class TextUtils {

    private TextUtils() {}

    /**
     * 截断字符串，超长时追加省略号
     * <p>
     * <pre>
     * truncate(null, 10)      → ""
     * truncate("abc", 10)     → "abc"
     * truncate("abcdefghij", 5) → "abcde..."
     * truncate("abcdefghij", 0) → ""
     * </pre>
     */
    public static String truncate(String s, int max) {
        if (s == null || max <= 0) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 先规范化空白再截断
     * <p>
     * 用途：日志/预览场景——把连续空白压成单空格、去掉首尾空白，
     * 避免日志里出现大段换行把输出撑爆。
     * <pre>
     * truncateWithClean("hello \n\n world", 100) → "hello world"
     * </pre>
     */
    public static String truncateWithClean(String s, int max) {
        if (s == null || max <= 0) {
            return "";
        }
        String clean = s.replaceAll("\\s+", " ").trim();
        return truncate(clean, max);
    }
}