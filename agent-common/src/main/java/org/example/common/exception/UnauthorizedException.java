package org.example.common.exception;

/**
 * 未认证异常——用于"未登录"、"Token 失效"等场景
 * <p>
 * 统一由 GlobalExceptionHandler 翻译成 401。
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException() {
        super("未登录");
    }

    public UnauthorizedException(String message) {
        super(message);
    }
}