package com.argus.rag.common.exception;

/**
 * 限流异常，抛出时由 {@link GlobalExceptionHandler} 统一处理，返回 HTTP 429。
 *
 * <p>由 {@code RateLimitAspect} 在 {@code RedisSlidingWindowRateLimiter.allow(...)}
 * 返回 {@code false} 时抛出，message 取自 {@code @RateLimit} 注解的提示信息。</p>
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }

    public RateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
