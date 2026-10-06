package org.example.api.common;

import lombok.Data;

/**
 * 全局统一响应体
 */
@Data
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    /**
     * 失败——不带数据
     * <p>
     * 保留——大多数场景不需要返回额外数据。
     */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return fail(code, message, null);
    }

    /**
     * 失败——带数据
     * <p>
     * 用于需要返回"错误详情"的场景，比如：
     * <ul>
     *   <li>DSL 校验失败——返回所有错误列表</li>
     *   <li>批量操作部分失败——返回失败明细</li>
     * </ul>
     */
    public static <T> ApiResponse<T> fail(int code, String message, T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code;
        r.message = message;
        r.data = data;
        return r;
    }
}